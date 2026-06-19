package com.example.civitai_dataset

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query
import java.io.File

// ================= DATA MODELS =================
data class CivitaiResponse(val items: List<CivitaiImage>)
data class CivitaiImage(val id: Long, val url: String, val meta: CivitaiMeta?)
data class CivitaiMeta(val prompt: String?)

// Модели для получения точечных данных о промпте через TRPC веб-сервиса
data class TrpcResponse(val result: TrpcResult)
data class TrpcResult(val data: TrpcData)
data class TrpcData(val json: TrpcJson?)
data class TrpcJson(val meta: TrpcMeta?)
data class TrpcMeta(val prompt: String?, val negativePrompt: String?)

data class DatasetItem(
    val instruction: String = "Создай подробный промпт для SDXL на основе следующего описания.",
    val input: String,
    val output: String
)

// ================= RETROFIT API =================
interface CivitaiApi {
    @GET("api/v1/images")
    suspend fun getImages(
        @Header("Authorization") authHeader: String?,
        @Query("limit") limit: Int = 100,
        @Query("sort") sort: String = "Most Reactions",
        @Query("period") period: String,
        @Query("nsfw") nsfw: Boolean?
    ): CivitaiResponse

    // Передаем токен авторизации также и в точечный TRPC-запрос для обхода ошибки 401
    @GET("api/trpc/image.getGenerationData")
    suspend fun getGenerationData(
        @Header("Authorization") authHeader: String?,
        @Query("input") inputJson: String
    ): TrpcResponse
}

object RetrofitClient {
    private const val BASE_URL = "https://civitai.com/"
    val api: CivitaiApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(CivitaiApi::class.java)
    }
}

// ================= MAIN ACTIVITY =================
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Состояние вкладок
    var activeTab by remember { mutableIntStateOf(0) } // 0 - Разметка, 1 - Статистика

    // Настройки запроса
    var selectedPeriod by remember { mutableStateOf("Week") }
    var nsfwEnabled by remember { mutableStateOf(false) }
    var apiToken by remember { mutableStateOf("") }

    // Данные изображений
    var images by remember { mutableStateOf<List<CivitaiImage>>(emptyList()) }
    var currentBatchIndex by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(false) }

    // Выбранная картинка для модального окна
    var selectedImage by remember { mutableStateOf<CivitaiImage?>(null) }
    var userDescription by remember { mutableStateOf("") }

    // Состояния для асинхронной загрузки промпта
    var promptLoading by remember { mutableStateOf(false) }
    var loadedPrompt by remember { mutableStateOf("") }

    // Локальный датасет
    var dataset by remember { mutableStateOf<List<DatasetItem>>(emptyList()) }

    // Загрузка датасета при старте
    LaunchedEffect(Unit) {
        dataset = loadDatasetFromFile(context)
    }

    // Слушатель выбора картинки: загружаем промпт в фоне при её открытии
    LaunchedEffect(selectedImage) {
        selectedImage?.let { img ->
            promptLoading = true
            loadedPrompt = "Загрузка промпта с сервера..."
            try {
                // Формируем TRPC-совместимый JSON запрос
                val jsonRequest = "{\"json\":{\"id\":${img.id}}}"
                
                // Передаем токен в заголовок запроса
                val authHeader = if (apiToken.isNotBlank()) "Bearer ${apiToken.trim()}" else null
                
                val response = RetrofitClient.api.getGenerationData(authHeader, jsonRequest)
                val prompt = response.result.data.json?.meta?.prompt
                loadedPrompt = prompt?.trim() ?: "Промпт для этого изображения скрыт автором или отсутствует."
            } catch (e: Exception) {
                loadedPrompt = "Ошибка загрузки промпта: ${e.localizedMessage}"
            } finally {
                promptLoading = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Шапка и Вкладки
        TabRow(selectedTabIndex = activeTab) {
            Tab(selected = activeTab == 0, onClick = { activeTab = 0 }) {
                Text("Разметка", modifier = Modifier.padding(16.dp))
            }
            Tab(selected = activeTab == 1, onClick = { activeTab = 1 }) {
                Text("Статистика (${dataset.size})", modifier = Modifier.padding(16.dp))
            }
        }

        if (activeTab == 0) {
            // ВКЛАДКА: РАЗМЕТКА
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Настройки
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Топ за: ", fontSize = 14.sp)
                        val periods = listOf("Day", "Week", "Month", "Year")
                        var expanded by remember { mutableStateOf(false) }
                        Box {
                            Text(
                                selectedPeriod,
                                modifier = Modifier
                                    .clickable { expanded = true }
                                    .padding(8.dp),
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                                periods.forEach { p ->
                                    DropdownMenuItem(
                                        text = { Text(p) },
                                        onClick = {
                                            selectedPeriod = p
                                            expanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = nsfwEnabled, onCheckedChange = { nsfwEnabled = it })
                        Text("NSFW", fontSize = 14.sp)
                    }

                    Button(
                        onClick = {
                            isLoading = true
                            scope.launch {
                                try {
                                    val authHeader = if (apiToken.isNotBlank()) "Bearer ${apiToken.trim()}" else null
                                    
                                    val response = RetrofitClient.api.getImages(
                                        authHeader = authHeader,
                                        period = selectedPeriod,
                                        nsfw = if (nsfwEnabled) true else null
                                    )
                                    
                                    // Теперь сохраняем ВСЕ картинки без фильтрации на старте
                                    images = response.items
                                    currentBatchIndex = 0
                                    
                                    Toast.makeText(
                                        context, 
                                        "Загружено изображений с сервера: ${images.size}", 
                                        Toast.LENGTH_LONG
                                    ).show()
                                    
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Ошибка сети: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                                } finally {
                                    isLoading = false
                                }
                            }
                        },
                        enabled = !isLoading
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Загрузить")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Поле для токена
                OutlinedTextField(
                    value = apiToken,
                    onValueChange = { apiToken = it },
                    label = { Text("API Token (Необязательно)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Сетка из 4 картинок
                if (images.isNotEmpty()) {
                    val batch = images.subList(
                        currentBatchIndex,
                        (currentBatchIndex + 4).coerceAtMost(images.size)
                    )

                    Box(modifier = Modifier.weight(1f)) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            itemsIndexed(batch) { _, item ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1f)
                                        .clickable { selectedImage = item }
                                ) {
                                    Box(modifier = Modifier.fillMaxSize()) {
                                        AsyncImage(
                                            model = item.url,
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Навигация
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { currentBatchIndex = (currentBatchIndex - 4).coerceAtLeast(0) },
                            enabled = currentBatchIndex > 0
                        ) {
                            Text("← Назад")
                        }

                        Text("Группа ${currentBatchIndex / 4 + 1} из ${images.size / 4 + 1}")

                        Button(
                            onClick = { currentBatchIndex = (currentBatchIndex + 4).coerceAtMost(images.size - 1) },
                            enabled = currentBatchIndex + 4 < images.size
                        ) {
                            Text("Вперед →")
                        }
                    }
                } else {
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text("Нажмите «Загрузить», чтобы получить изображения", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            // ВКЛАДКА: СТАТИСТИКА И БАЗА
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Карточки со статистикой
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Card(modifier = Modifier.weight(1f)) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text("Всего записей", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${dataset.size}", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    val avgInput = if (dataset.isNotEmpty()) dataset.map { it.input.length }.average().toInt() else 0
                    val avgOutput = if (dataset.isNotEmpty()) dataset.map { it.output.length }.average().toInt() else 0
                    Card(modifier = Modifier.weight(1f)) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text("Ср. Input (симв)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("$avgInput", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Card(modifier = Modifier.weight(1f)) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text("Ср. Output (симв)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("$avgOutput", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Кнопка экспорта
                Button(
                    onClick = {
                        exportDataset(context, dataset)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = dataset.isNotEmpty()
                ) {
                    Text("Экспортировать датасет (Share JSON)")
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Список записей с возможностью удаления
                LazyColumn(modifier = Modifier.weight(1f)) {
                    itemsIndexed(dataset) { index, item ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Input: ${item.input}", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("Output: ${item.output}", fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                                IconButton(onClick = {
                                    val mutableList = dataset.toMutableList()
                                    mutableList.removeAt(index)
                                    dataset = mutableList
                                    saveDatasetToFile(context, dataset)
                                }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // МОДАЛЬНОЕ ОКНО ДЛЯ РАЗМЕТКИ
    selectedImage?.let { img ->
        Dialog(onDismissRequest = { selectedImage = null }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f),
                shape = MaterialTheme.shapes.large
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    // Картинка
                    AsyncImage(
                        model = img.url,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                        contentScale = ContentScale.Fit
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Прокручиваемый контент формы
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text("1. Опишите картинку своими словами (Input):", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = userDescription,
                            onValueChange = { userDescription = it },
                            placeholder = { Text("Например: футуристичная девушка на фоне неонового города...") },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 4
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Text("2. Оригинальный промпт (Output):", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                        Spacer(modifier = Modifier.height(4.dp))
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                text = if (promptLoading) "Загрузка промпта с сервера..." else loadedPrompt,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Кнопки сохранения/отмены
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { selectedImage = null }) {
                            Text("Отмена")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (userDescription.isNotBlank() && !promptLoading && !loadedPrompt.startsWith("Ошибка") && !loadedPrompt.startsWith("Загрузка")) {
                                    val newItem = DatasetItem(
                                        input = userDescription,
                                        output = loadedPrompt
                                    )
                                    dataset = dataset + newItem
                                    saveDatasetToFile(context, dataset)
                                    selectedImage = null
                                    userDescription = ""
                                    Toast.makeText(context, "Сохранено!", Toast.LENGTH_SHORT).show()
                                } else {
                                    if (promptLoading) {
                                        Toast.makeText(context, "Пожалуйста, дождитесь загрузки промпта", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Введите описание и проверьте промпт!", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        ) {
                            Text("Сохранить")
                        }
                    }
                }
            }
        }
    }
}

// ================= FILE STORAGE & EXPORT =================
private const val FILE_NAME = "dataset.json"

private fun getDatasetFile(context: Context): File {
    return File(context.filesDir, FILE_NAME)
}

private fun loadDatasetFromFile(context: Context): List<DatasetItem> {
    val file = getDatasetFile(context)
    if (!file.exists()) return emptyList()
    return try {
        val json = file.readText()
        val type = object : TypeToken<List<DatasetItem>>() {}.type
        Gson().fromJson(json, type) ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }
}

private fun saveDatasetToFile(context: Context, dataset: List<DatasetItem>) {
    val file = getDatasetFile(context)
    try {
        val gson = GsonBuilder().setPrettyPrinting().create()
        val json = gson.toJson(dataset)
        file.writeText(json)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

private fun exportDataset(context: Context, dataset: List<DatasetItem>) {
    try {
        val gson = GsonBuilder().setPrettyPrinting().create()
        val json = gson.toJson(dataset)

        // Записываем файл во временную директорию (кэш) для экспорта
        val exportFile = File(context.cacheDir, "dataset.json")
        exportFile.writeText(json)

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            exportFile
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Экспортировать JSON"))
    } catch (e: Exception) {
        Toast.makeText(context, "Ошибка экспорта: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
    }
}
