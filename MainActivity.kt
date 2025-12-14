@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.v12

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import io.github.openspacedrepetition.Card
import io.github.openspacedrepetition.Scheduler
import io.github.openspacedrepetition.Rating as FsrsRating
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import kotlin.math.min
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { App() } }
    }
}

/** Your UI ratings */
enum class Rating { AGAIN, HARD, GOOD, EASY }

data class Lesson(val id: Long, val title: String)
data class State(
    val lessonId: Long,
    val step: Int = 1,                  // Step 1..5 (UI only)
    val dueAt: Long = System.currentTimeMillis(),
    val isManual: Boolean = false,
    val reviewCount: Int = 0,
    val fsrsCardJson: String? = null
)
data class Item(val lesson: Lesson, val state: State)

/** --- FSRS scheduler config (method 2) --- */
private fun buildScheduler(): Scheduler {
    return Scheduler.builder()
        .desiredRetention(0.9)                 // common default in FSRS world :contentReference[oaicite:2]{index=2}
        .learningSteps(emptyArray<Duration>()) // disable minute-based steps :contentReference[oaicite:3]{index=3}
        .relearningSteps(emptyArray<Duration>())
        // Disable fuzzing so the "Next" preview matches the scheduled due time exactly.
        .enableFuzzing(false)
        .build()
}

private fun loadCard(json: String?): Card {
    return try {
        if (json.isNullOrBlank()) Card.builder().build()
        else Card.fromJson(json)
    } catch (_: Throwable) {
        Card.builder().build()
    }
}

private fun stepFromReviewCount(rc: Int): Int {
    return when {
        rc <= 0 -> 1
        rc == 1 -> 2
        rc in 2..3 -> 3
        rc in 4..6 -> 4
        else -> 5
    }
}

private fun LessonEntity.toItem(): Item {
    return Item(
        lesson = Lesson(id, title),
        state = State(
            lessonId = id,
            step = box,
            dueAt = dueAt,
            isManual = isManual,
            reviewCount = reviewCount,
            fsrsCardJson = fsrsCardJson
        )
    )
}

class Vm(
    private val appContext: android.content.Context,
    private val lessonDao: LessonDao,
    private val projectDao: ProjectDao
) : ViewModel() {

    private val scheduler: Scheduler = buildScheduler()

    val itemsFlow = lessonDao.observeAll().map { list ->
        list.map { it.toItem() }.sortedBy { it.state.dueAt }
    }

    suspend fun addLesson(title: String) = withContext(Dispatchers.IO) {
        val clean = title.trim()
        if (clean.isEmpty()) return@withContext

        val card = Card.builder().build()
        val entity = LessonEntity(
            title = clean,
            projectId = 1,
            box = 1,
            dueAt = System.currentTimeMillis(),
            isManual = false,
            reviewCount = 0,
            fsrsCardJson = card.toJson()
        )
        lessonDao.insert(entity)
    }

    suspend fun updateLessonTitle(id: Long, newTitle: String) = withContext(Dispatchers.IO) {
        val e = lessonDao.getById(id) ?: return@withContext
        lessonDao.update(e.copy(title = newTitle.trim()))
    }

    suspend fun deleteLesson(id: Long) = withContext(Dispatchers.IO) {
        NotificationScheduler.cancel(appContext, id)
        lessonDao.deleteById(id)
    }

    suspend fun setManualDue(id: Long, atMillis: Long) = withContext(Dispatchers.IO) {
        val e = lessonDao.getById(id) ?: return@withContext
        val updated = e.copy(dueAt = atMillis, isManual = true)
        lessonDao.update(updated)
        NotificationScheduler.schedule(appContext, updated.id, updated.title, updated.dueAt)
    }

    suspend fun clearManualBackToAuto(id: Long) = withContext(Dispatchers.IO) {
        val e = lessonDao.getById(id) ?: return@withContext
        val card = loadCard(e.fsrsCardJson)
        val autoDue = card.getDue().toEpochMilli()
        val updated = e.copy(dueAt = autoDue, isManual = false)
        lessonDao.update(updated)
        NotificationScheduler.schedule(appContext, updated.id, updated.title, updated.dueAt)
    }

    suspend fun rate(id: Long, rating: Rating) = withContext(Dispatchers.IO) {
        val e = lessonDao.getById(id) ?: return@withContext

        val fsrsRating = FsrsRating.valueOf(rating.name) // AGAIN/HARD/GOOD/EASY :contentReference[oaicite:4]{index=4}
        val oldCard = loadCard(e.fsrsCardJson)
        val result = scheduler.reviewCard(oldCard, fsrsRating)
        val newCard = result.card()
        val newDue = newCard.getDue().toEpochMilli()

        val newReviewCount = e.reviewCount + 1
        val newBox = stepFromReviewCount(newReviewCount)

        val updated = e.copy(
            box = newBox,
            dueAt = newDue,
            isManual = false,
            reviewCount = newReviewCount,
            fsrsCardJson = newCard.toJson()
        )
        lessonDao.update(updated)

        // schedule reminder for the next due time
        NotificationScheduler.schedule(appContext, updated.id, updated.title, updated.dueAt)
    }

    suspend fun firstDueNowId(now: Long = System.currentTimeMillis()): Long? = withContext(Dispatchers.IO) {
        lessonDao.getFirstDue(now)?.id
    }
}

class VmFactory(private val appContext: android.content.Context, private val db: AppDatabase) :
    ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return Vm(appContext, db.lessonDao(), db.projectDao()) as T
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val db = remember { AppDatabase.get(ctx) }
    val factory = remember { VmFactory(ctx.applicationContext, db) }
    val vm: Vm = viewModel(factory = factory)

    val items by vm.itemsFlow.collectAsState(initial = emptyList())
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()

    fun dueCount(now: Long = System.currentTimeMillis()) = items.count { it.state.dueAt <= now }

    NavHost(nav, startDestination = "home") {

        composable("home") {
            Home(
                items = items,
                dueCount = dueCount(),
                onAdd = { nav.navigate("add") },
                onOpen = { nav.navigate("review/$it") },
                onEdit = { nav.navigate("edit/$it") },
                onStartDue = {
                    scope.launch {
                        val nextId = vm.firstDueNowId()
                        if (nextId != null) nav.navigate("review/$nextId")
                    }
                }
            )
        }

        composable("add") {
            Add(
                onBack = { nav.popBackStack() },
                onSave = { title ->
                    scope.launch {
                        vm.addLesson(title)
                        nav.popBackStack()
                    }
                }
            )
        }

        composable(
            "review/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry ->
            val id = entry.arguments?.getLong("id") ?: 0L
            val item = items.firstOrNull { it.lesson.id == id }

            if (item == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Not found") }
            } else {
                Review(
                    item = item,
                    dueNowCount = dueCount(),
                    onBack = { nav.popBackStack() },
                    onRate = { r ->
                        scope.launch {
                            vm.rate(id, r)
                            val nextId = vm.firstDueNowId()
                            if (nextId != null) {
                                nav.navigate("review/$nextId") { popUpTo("home") }
                            } else {
                                nav.navigate("home") { popUpTo("home") { inclusive = true } }
                            }
                        }
                    }
                )
            }
        }

        composable(
            "edit/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry ->
            val id = entry.arguments?.getLong("id") ?: 0L
            val item = items.firstOrNull { it.lesson.id == id }

            if (item == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Not found") }
            } else {
                EditScreen(
                    item = item,
                    onBack = { nav.popBackStack() },
                    onSaveTitle = { newTitle ->
                        scope.launch {
                            vm.updateLessonTitle(id, newTitle)
                            nav.popBackStack()
                        }
                    },
                    onSetManual = { atMillis ->
                        scope.launch { vm.setManualDue(id, atMillis) }
                    },
                    onClearManual = {
                        scope.launch { vm.clearManualBackToAuto(id) }
                    },
                    onDelete = {
                        scope.launch {
                            vm.deleteLesson(id)
                            nav.navigate("home") { popUpTo("home") { inclusive = true } }
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun Home(
    items: List<Item>,
    dueCount: Int,
    onAdd: () -> Unit,
    onOpen: (Long) -> Unit,
    onEdit: (Long) -> Unit,
    onStartDue: () -> Unit
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Topics") }) },
        floatingActionButton = { FloatingActionButton(onClick = onAdd) { Text("+") } }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Due now: $dueCount", modifier = Modifier.weight(1f))
                    Button(onClick = onStartDue, enabled = dueCount > 0) { Text("Start") }
                }
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(items) { item ->
                    LessonRow(
                        item = item,
                        onOpen = { onOpen(item.lesson.id) },
                        onEdit = { onEdit(item.lesson.id) }
                    )
                }
            }
        }
    }
}

@Composable
fun LessonRow(item: Item, onOpen: () -> Unit, onEdit: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onOpen() }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(item.lesson.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val manualTag = if (item.state.isManual) " • Manual" else ""
                Text("Step ${item.state.step}$manualTag • Next: ${formatDue(item.state.dueAt)}")
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit")
            }
        }
    }
}

@Composable
fun Add(onBack: () -> Unit, onSave: (String) -> Unit) {
    var title by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add topic") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Topic name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Button(onClick = { onSave(title) }, enabled = title.trim().isNotEmpty()) { Text("Save") }
        }
    }
}

@Composable
fun Review(item: Item, dueNowCount: Int, onBack: () -> Unit, onRate: (Rating) -> Unit) {
    val scheduler = remember { buildScheduler() }
    val baseCard = remember(item.state.fsrsCardJson) { loadCard(item.state.fsrsCardJson) }

    fun previewDue(r: FsrsRating): Long {
        val result = scheduler.reviewCard(baseCard, r)
        return result.card().getDue().toEpochMilli()
    }

    val againDue = remember(item.lesson.id, item.state.fsrsCardJson) { previewDue(FsrsRating.AGAIN) }
    val hardDue = remember(item.lesson.id, item.state.fsrsCardJson) { previewDue(FsrsRating.HARD) }
    val goodDue = remember(item.lesson.id, item.state.fsrsCardJson) { previewDue(FsrsRating.GOOD) }
    val easyDue = remember(item.lesson.id, item.state.fsrsCardJson) { previewDue(FsrsRating.EASY) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Review") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text(item.lesson.title, style = MaterialTheme.typography.headlineSmall)
                    Text("Now: Step ${item.state.step} • Queue: $dueNowCount")
                    Text("Current due: ${formatDue(item.state.dueAt)}")
                }
            }

            Button(onClick = { onRate(Rating.AGAIN) }, modifier = Modifier.fillMaxWidth()) {
                Text("Relearn • Next: ${formatDue(againDue)}")
            }
            OutlinedButton(onClick = { onRate(Rating.HARD) }, modifier = Modifier.fillMaxWidth()) {
                Text("Hard • Next: ${formatDue(hardDue)}")
            }
            Button(onClick = { onRate(Rating.GOOD) }, modifier = Modifier.fillMaxWidth()) {
                Text("Good • Next: ${formatDue(goodDue)}")
            }
            Button(onClick = { onRate(Rating.EASY) }, modifier = Modifier.fillMaxWidth()) {
                Text("Easy • Next: ${formatDue(easyDue)}")
            }
        }
    }
}

@Composable
fun EditScreen(
    item: Item,
    onBack: () -> Unit,
    onSaveTitle: (String) -> Unit,
    onSetManual: (Long) -> Unit,
    onClearManual: () -> Unit,
    onDelete: () -> Unit
) {
    val ctx = LocalContext.current
    var title by remember { mutableStateOf(item.lesson.title) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun pickManualDateTime() {
        val now = Calendar.getInstance()
        DatePickerDialog(
            ctx,
            { _, y, m, d ->
                val now2 = Calendar.getInstance()
                TimePickerDialog(
                    ctx,
                    { _, hh, mm ->
                        val c = Calendar.getInstance().apply {
                            set(Calendar.YEAR, y)
                            set(Calendar.MONTH, m)
                            set(Calendar.DAY_OF_MONTH, d)
                            set(Calendar.HOUR_OF_DAY, hh)
                            set(Calendar.MINUTE, mm)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        onSetManual(c.timeInMillis)
                    },
                    now2.get(Calendar.HOUR_OF_DAY),
                    now2.get(Calendar.MINUTE),
                    true
                ).show()
            },
            now.get(Calendar.YEAR),
            now.get(Calendar.MONTH),
            now.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit topic") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Topic name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Button(
                onClick = { onSaveTitle(title) },
                enabled = title.trim().isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save title") }

            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Next review: ${formatDue(item.state.dueAt)}")
                    Text(if (item.state.isManual) "Reminder: Manual" else "Reminder: Auto (FSRS)")
                }
            }

            OutlinedButton(
                onClick = { pickManualDateTime() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Set manual reminder…") }

            OutlinedButton(
                onClick = { onClearManual() },
                enabled = item.state.isManual,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Back to auto scheduling") }

            OutlinedButton(
                onClick = { confirmDelete = true },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Delete topic") }

            if (confirmDelete) {
                AlertDialog(
                    onDismissRequest = { confirmDelete = false },
                    title = { Text("Delete?") },
                    text = { Text("This will remove the topic and its reminder.") },
                    confirmButton = {
                        TextButton(onClick = { confirmDelete = false; onDelete() }) {
                            Text("Delete")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmDelete = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }
        }
    }
}

private val dueFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE, MMM d • HH:mm")

private fun formatDue(epochMillis: Long): String {
    return Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(dueFormatter)
}
