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
import androidx.compose.material.icons.filled.Edit
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
import coil.Coil
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.disk.DiskCache
import coil.memory.MemoryCache
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
data class CivitaiResponse(val items: List<CivitaiImage>, val metadata: CivitaiMetadata?)
data class CivitaiMetadata(val nextCursor: String?)

data class CivitaiImage(
    val id: Long, 
    val url: String, 
    val username: String?, // Никнейм автора картинки
    val meta: CivitaiMeta?
)
data class CivitaiMeta(val prompt: String?)

// Модели для TRPC-запроса промпта
data class TrpcResponse(val result: TrpcResult)
data class TrpcResult(val data: TrpcData)
data class TrpcData(val json: TrpcJson?)
data class TrpcJson(val meta: TrpcMeta?)
data class TrpcMeta(val prompt: String?, val negativePrompt: String?)

data class DatasetItem(
    val instruction: String = "Создай подробный промпт для SDXL на основе следующего описания.",
    var input: String,
    var output: String
)

// ================= RETROFIT API =================
interface CivitaiApi {
    @GET("api/v1/images")
    suspend fun getImages(
        @Header("Authorization") authHeader: String?,
        @Query("limit") limit: Int = 100,
        @Query("sort") sort: String = "Most Reactions",
        @Query("period") period: String,
        @Query("nsfw") nsfw: Boolean?,
        @Query("username") username: String?, // Поиск по автору
        @Query("cursor") cursor: String? // Курсор для пагинации
    ): CivitaiResponse

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

        // Инициализация кэш-менеджера Coil (Лимит 1 ГБ диска, 25% ОЗУ)
        val imageLoader = ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(this.cacheDir.resolve("image_cache"))
                    .maxSizeBytes(1024 * 1024 * 1024)
                    .build()
            }
            .build()
        
        Coil.setImageLoader(imageLoader)

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

    var activeTab by remember { mutableIntStateOf(0) } 

    // Настройки запроса
    var selectedPeriod by remember { mutableStateOf("Week") }
    var selectedSort by remember { mutableStateOf("Most Reactions") } // "Most Reactions" или "Newest"
    var nsfwEnabled by remember { mutableStateOf(false) }
    var apiToken by remember { mutableStateOf("") }
    var activeUsername by remember { mutableStateOf("") } // Активный фильтр по автору

    // Данные изображений и пагинации
    var images by remember { mutableStateOf<List<CivitaiImage>>(emptyList()) }
    var currentBatchIndex by remember { mutableIntStateOf(0) }
    var nextCursor by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    // Выбранная картинка для разметки
    var selectedImage by remember { mutableStateOf<CivitaiImage?>(null) }
    var userDescription by remember { mutableStateOf("") }

    var promptLoading by remember { mutableStateOf(false) }
    var loadedPrompt by remember { mutableStateOf("") }

    // Управление базами данных (файлами)
    var currentDbName by remember { mutableStateOf("dataset") }
    var dbList by remember { mutableStateOf(listOf("dataset")) }
    var showCreateDbDialog by remember { mutableStateOf(false) }
    var showDeleteDbDialog by remember { mutableStateOf(false) }
    var newDbNameInput by remember { mutableStateOf("") }

    // Локальный датасет
    var dataset by remember { mutableStateOf<List<DatasetItem>>(emptyList()) }

    // Поиск по текущей базе
    var searchQuery by remember { mutableStateOf("") }

    // Состояния для редактирования записи
    var editingItemIndex by remember { mutableStateOf<Int?>(null) }
    var editInputText by remember { mutableStateOf("") }
    var editOutputText by remember { mutableStateOf("") }

    // Загрузка баз при старте
    LaunchedEffect(Unit) {
        dbList = getDatabaseList(context)
        dataset = loadDatasetFromFile(context, currentDbName)
    }

    // Слушатель смены активной базы данных
    LaunchedEffect(currentDbName) {
        dataset = loadDatasetFromFile(context, currentDbName)
    }

    // Загрузка промпта при открытии диалога картинки
    LaunchedEffect(selectedImage) {
        selectedImage?.let { img ->
            promptLoading = true
            loadedPrompt = "Загрузка промпта с сервера..."
            try {
                val jsonRequest = "{\"json\":{\"id\":${img.id}}}"
                val authHeader = if (apiToken.isNotBlank()) "Bearer ${apiToken.trim()}" else null
                val response = RetrofitClient.api.getGenerationData(authHeader, jsonRequest)
                val prompt = response.result.data.json?.meta?.prompt
                loadedPrompt = prompt?.trim() ?: "Промпт скрыт автором или отсутствует."
            } catch (e: Exception) {
                loadedPrompt = "Ошибка загрузки промпта: ${e.localizedMessage}"
            } finally {
                promptLoading = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Вкладки
        TabRow(selectedTabIndex = activeTab) {
            Tab(selected = activeTab == 0, onClick = { activeTab = 0 }) {
                Text("Разметка [${currentDbName}]", modifier = Modifier.padding(16.dp))
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
                // Строка настроек и запуска загрузки
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        // Топ за период
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Топ за: ", fontSize = 12.sp)
                            val periods = listOf("Day", "Week", "Month", "Year")
                            var expanded by remember { mutableStateOf(false) }
                            Box {
                                Text(
                                    selectedPeriod,
                                    modifier = Modifier
                                        .clickable { expanded = true }
                                        .padding(4.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
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
                        // Сортировка (Популярные / Новые)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Сорт: ", fontSize = 12.sp)
                            val sorts = mapOf("Most Reactions" to "Популярные", "Newest" to "Новые")
                            var expandedSort by remember { mutableStateOf(false) }
                            Box {
                                Text(
                                    sorts[selectedSort] ?: "Популярные",
                                    modifier = Modifier
                                        .clickable { expandedSort = true }
                                        .padding(4.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                                DropdownMenu(expanded = expandedSort, onDismissRequest = { expandedSort = false }) {
                                    sorts.forEach { (key, name) ->
                                        DropdownMenuItem(
                                            text = { Text(name) },
                                            onClick = {
                                                selectedSort = key
                                                expandedSort = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = nsfwEnabled, onCheckedChange = { nsfwEnabled = it })
                        Text("NSFW", fontSize = 12.sp)
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
                                        nsfw = if (nsfwEnabled) true else null,
                                        sort = selectedSort,
                                        username = activeUsername.ifBlank { null },
                                        cursor = null // Сбрасываем курсор на первую страницу при новом запросе
                                    )
                                    images = response.items
                                    nextCursor = response.metadata?.nextCursor
                                    currentBatchIndex = 0
                                    Toast.makeText(context, "Загружено изображений: ${images.size}", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Ошибка сети: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                                } finally {
                                    isLoading = false
                                }
                            }
                        },
                        enabled = !isLoading
                    ) {
                        if (isLoading && currentBatchIndex == 0) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Загрузить", fontSize = 12.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Фильтр-плашка активного автора (если выбран)
                if (activeUsername.isNotBlank()) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("🔍 Работы автора: @$activeUsername", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text(
                                "Сбросить [X]",
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .clickable {
                                        activeUsername = ""
                                        // Автозапуск загрузки без фильтра автора
                                        isLoading = true
                                        scope.launch {
                                            try {
                                                val authHeader = if (apiToken.isNotBlank()) "Bearer ${apiToken.trim()}" else null
                                                val response = RetrofitClient.api.getImages(
                                                    authHeader = authHeader,
                                                    period = selectedPeriod,
                                                    nsfw = if (nsfwEnabled) true else null,
                                                    sort = selectedSort,
                                                    username = null,
                                                    cursor = null
                                                )
                                                images = response.items
                                                nextCursor = response.metadata?.nextCursor
                                                currentBatchIndex = 0
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "Ошибка сети: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                                            } finally {
                                                isLoading = false
                                            }
                                        }
                                    }
                                    .padding(4.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                OutlinedTextField(
                    value = apiToken,
                    onValueChange = { apiToken = it },
                    label = { Text("API Token (Необязательно)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))

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

                    // Навигация (с поддержкой дозагрузки)
                    val isLastBatch = currentBatchIndex + 4 >= images.size
                    val canLoadMore = nextCursor != null

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { currentBatchIndex = (currentBatchIndex - 4).coerceAtLeast(0) },
                            enabled = currentBatchIndex > 0
                        ) {
                            Text("← Назад")
                        }

                        Text("Позиция ${currentBatchIndex + 1} из ${images.size}", fontSize = 12.sp)

                        Button(
                            onClick = {
                                if (isLastBatch && canLoadMore) {
                                    // Авто-подгрузка следующих 100 изображений по токену курсора
                                    isLoading = true
                                    scope.launch {
                                        try {
                                            val authHeader = if (apiToken.isNotBlank()) "Bearer ${apiToken.trim()}" else null
                                            val response = RetrofitClient.api.getImages(
                                                authHeader = authHeader,
                                                period = selectedPeriod,
                                                nsfw = if (nsfwEnabled) true else null,
                                                sort = selectedSort,
                                                username = activeUsername.ifBlank { null },
                                                cursor = nextCursor
                                            )
                                            images = images + response.items
                                            nextCursor = response.metadata?.nextCursor
                                            currentBatchIndex += 4
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "Ошибка загрузки: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                                        } finally {
                                            isLoading = false
                                        }
                                    }
                                } else {
                                    currentBatchIndex = (currentBatchIndex + 4).coerceAtMost(images.size - 1)
                                }
                            },
                            enabled = (!isLastBatch || canLoadMore) && !isLoading
                        ) {
                            if (isLoading && isLastBatch) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Text(if (isLastBatch) "Загрузить еще" else "Вперед →")
                            }
                        }
                    }
                } else {
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text("Нажмите «Загрузить», чтобы получить изображения\nЗапись ведется в базу: $currentDbName.json", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        } else {
            // ВКЛАДКА: СТАТИСТИКА, УПРАВЛЕНИЕ БАЗАМИ И ПОИСК
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Панель выбора, создания и удаления баз данных
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    var expandedDbMenu by remember { mutableStateOf(false) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("База: ", fontWeight = FontWeight.Bold)
                        Box {
                            Text(
                                "$currentDbName.json",
                                modifier = Modifier
                                    .clickable { expandedDbMenu = true }
                                    .padding(8.dp),
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            DropdownMenu(expanded = expandedDbMenu, onDismissRequest = { expandedDbMenu = false }) {
                                dbList.forEach { db ->
                                    DropdownMenuItem(
                                        text = { Text("$db.json") },
                                        onClick = {
                                            currentDbName = db
                                            expandedDbMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(
                            onClick = { showCreateDbDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text("Создать", fontSize = 12.sp)
                        }

                        Button(
                            onClick = { showDeleteDbDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text("Удалить", fontSize = 12.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

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

                Spacer(modifier = Modifier.height(12.dp))

                // Поисковая строка для фильтрации
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Поиск по базе...") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Ряд кнопок для действий
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { exportDataset(context, dataset, currentDbName) },
                        modifier = Modifier.weight(1f),
                        enabled = dataset.isNotEmpty(),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text("Поделиться базой", fontSize = 13.sp)
                    }

                    Button(
                        onClick = {
                            Coil.imageLoader(context).let { loader ->
                                loader.memoryCache?.clear()
                                loader.diskCache?.clear()
                            }
                            Toast.makeText(context, "Кэш картинок успешно очищен!", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text("Очистить кэш (1 ГБ)", fontSize = 13.sp)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Список записей с фильтрацией поиска
                val filteredDataset = dataset.filter {
                    it.input.contains(searchQuery, ignoreCase = true) || 
                    it.output.contains(searchQuery, ignoreCase = true)
                }

                LazyColumn(modifier = Modifier.weight(1f)) {
                    itemsIndexed(filteredDataset) { index, item ->
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
                                Row {
                                    // Кнопка редактирования
                                    IconButton(onClick = {
                                        editingItemIndex = index
                                        editInputText = item.input
                                        editOutputText = item.output
                                    }) {
                                        Icon(Icons.Default.Edit, contentDescription = "Edit", tint = MaterialTheme.colorScheme.primary)
                                    }
                                    // Кнопка удаления
                                    IconButton(onClick = {
                                        val mutableList = dataset.toMutableList()
                                        mutableList.removeAt(index)
                                        dataset = mutableList
                                        saveDatasetToFile(context, dataset, currentDbName)
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
    }

    // ДИАЛОГ СОЗДАНИЯ НОВОЙ БАЗЫ ДАННЫХ
    if (showCreateDbDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDbDialog = false },
            title = { Text("Создать новую базу данных") },
            text = {
                Column {
                    Text("Введите имя базы (без расширения .json):", fontSize = 14.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newDbNameInput,
                        onValueChange = { newDbNameInput = it.replace(Regex("[^a-zA-Z0-9_]"), "") },
                        placeholder = { Text("Например: anime_db") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val cleanedName = newDbNameInput.trim()
                    if (cleanedName.isNotBlank()) {
                        currentDbName = cleanedName
                        dataset = emptyList() // Новая база изначально пуста
                        saveDatasetToFile(context, dataset, currentDbName)
                        dbList = getDatabaseList(context)
                        newDbNameInput = ""
                        showCreateDbDialog = false
                        Toast.makeText(context, "База $cleanedName.json успешно создана", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Text("Создать")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDbDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }

    // ДИАЛОГ УДАЛЕНИЯ ТЕКУЩЕЙ БАЗЫ ДАННЫХ
    if (showDeleteDbDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDbDialog = false },
            title = { Text("Удалить базу данных") },
            text = {
                Text("Вы действительно хотите полностью стереть базу данных \"$currentDbName.json\"? Все записи в ней будут безвозвратно удалены.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        val file = getDatasetFile(context, currentDbName)
                        if (file.exists()) {
                            file.delete()
                        }
                        
                        val remaining = getDatabaseList(context)
                        if (remaining.isEmpty()) {
                            currentDbName = "dataset"
                            saveDatasetToFile(context, emptyList(), "dataset")
                        } else {
                            currentDbName = remaining.first()
                        }
                        
                        dbList = getDatabaseList(context)
                        showDeleteDbDialog = false
                        Toast.makeText(context, "База данных успешно удалена", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Удалить")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDbDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }

    // ДИАЛОГ РЕДАКТИРОВАНИЯ ЗАПИСИ
    editingItemIndex?.let { index ->
        Dialog(onDismissRequest = { editingItemIndex = null }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.8f),
                shape = MaterialTheme.shapes.large
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    Text("Редактирование записи", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(modifier = Modifier.height(16.dp))

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text("Изменить описание (Input):", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = editInputText,
                            onValueChange = { editInputText = it },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 4
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Text("Изменить промпт (Output):", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedTextField(
                            value = editOutputText,
                            onValueChange = { editOutputText = it },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 8
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { editingItemIndex = null }) {
                            Text("Отмена")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = {
                            if (editInputText.isNotBlank() && editOutputText.isNotBlank()) {
                                val mutableList = dataset.toMutableList()
                                mutableList[index] = DatasetItem(
                                    input = editInputText,
                                    output = editOutputText
                                )
                                dataset = mutableList
                                saveDatasetToFile(context, dataset, currentDbName)
                                editingItemIndex = null
                                Toast.makeText(context, "Запись обновлена!", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Поля не могут быть пустыми!", Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Text("Применить")
                        }
                    }
                }
            }
        }
    }

    // МОДАЛЬНОЕ ОКНО ДЛЯ РАЗМЕТКИ КАРТИНКИ
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
                    AsyncImage(
                        model = img.url,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                        contentScale = ContentScale.Fit
                    )

                    Spacer(modifier = Modifier.height(16.dp))

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

                        // Блок отображения автора картинки
                        img.username?.let { name ->
                            Text(
                                text = "👤 Автор: @$name",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable {
                                        activeUsername = name
                                        selectedImage = null // Закрываем диалог
                                        
                                        // Автозапуск загрузки работ автора
                                        isLoading = true
                                        scope.launch {
                                            try {
                                                val authHeader = if (apiToken.isNotBlank()) "Bearer ${apiToken.trim()}" else null
                                                val response = RetrofitClient.api.getImages(
                                                    authHeader = authHeader,
                                                    period = selectedPeriod,
                                                    nsfw = if (nsfwEnabled) true else null,
                                                    sort = selectedSort,
                                                    username = name,
                                                    cursor = null
                                                )
                                                images = response.items
                                                nextCursor = response.metadata?.nextCursor
                                                currentBatchIndex = 0
                                            } catch (e: Exception) {
                                                Toast.makeText(context, "Ошибка сети: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                                            } finally {
                                                isLoading = false
                                            }
                                        }
                                    }
                                    .padding(vertical = 4.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

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
                                    saveDatasetToFile(context, dataset, currentDbName)
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

// Получение списка всех сохраненных .json баз данных на устройстве
private fun getDatabaseList(context: Context): List<String> {
    val files = context.filesDir.listFiles() ?: return listOf("dataset")
    val dbNames = files.filter { it.name.endsWith(".json") }
                       .map { it.name.substringBeforeLast(".json") }
    return if (dbNames.isEmpty()) listOf("dataset") else dbNames
}

private fun getDatasetFile(context: Context, dbName: String): File {
    return File(context.filesDir, "$dbName.json")
}

private fun loadDatasetFromFile(context: Context, dbName: String): List<DatasetItem> {
    val file = getDatasetFile(context, dbName)
    if (!file.exists()) return emptyList()
    return try {
        val json = file.readText()
        val type = object : TypeToken<List<DatasetItem>>() {}.type
        Gson().fromJson(json, type) ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }
}

private fun saveDatasetToFile(context: Context, dataset: List<DatasetItem>, dbName: String) {
    val file = getDatasetFile(context, dbName)
    try {
        val gson = GsonBuilder().setPrettyPrinting().create()
        val json = gson.toJson(dataset)
        file.writeText(json)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

private fun exportDataset(context: Context, dataset: List<DatasetItem>, dbName: String) {
    try {
        val gson = GsonBuilder().setPrettyPrinting().create()
        val json = gson.toJson(dataset)

        val exportFile = File(context.cacheDir, "$dbName.json")
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
