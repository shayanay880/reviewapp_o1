@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.v12
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.ui.platform.LocalContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewModelScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.flow.firstOrNull
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.min

class MainActivity : ComponentActivity() {

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* ignore */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ask for POST_NOTIFICATIONS on Android 13+
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent { MaterialTheme { App() } }
    }
}

enum class Rating { AGAIN, HARD, GOOD, EASY }

data class Lesson(val id: Long, val title: String)

data class State(
    val lessonId: Long,
    val step: Int = 1,
    val dueAt: Long = System.currentTimeMillis(),
    val isManual: Boolean = false
)

data class Item(val lesson: Lesson, val state: State)
private fun LessonEntity.toItem(): Item =
    Item(
        lesson = Lesson(id = id, title = title),
        state = State(lessonId = id, step = box, dueAt = dueAt, isManual = isManual)
    )
class Vm(
    private val lessonDao: LessonDao,
    private val projectDao: ProjectDao
) : ViewModel() {

    private val daysForBox = listOf(1, 3, 5, 10, 20)
    private val dayMs = 24L * 60L * 60L * 1000L

    // observe list for UI
    val itemsFlow: Flow<List<Item>> =
        lessonDao.observeAll().map { list -> list.map { it.toItem() } }

    val projectsFlow: Flow<List<ProjectEntity>> =
        projectDao.observeAll()

    fun itemsByProjectFlow(projectId: Long): Flow<List<Item>> =
        lessonDao.observeByProject(projectId).map { list -> list.map { it.toItem() } }

    fun observeItem(id: Long): Flow<Item?> =
        lessonDao.observeById(id).map { it?.toItem() }

    init {
        // seed once (only first install)
        viewModelScope.launch {
            if (projectDao.count() == 0) {
                projectDao.insert(ProjectEntity(id = 1, name = "General"))
            }
            if (lessonDao.count() == 0) {
                lessonDao.insert(LessonEntity(title = "CXR interpretation", projectId = 1))
                lessonDao.insert(LessonEntity(title = "ECG basics", projectId = 1))
            }
        }
    }

    suspend fun addLesson(title: String, projectId: Long = 1L) {
        val t = title.trim()
        if (t.isEmpty()) return
        lessonDao.insert(LessonEntity(title = t, projectId = projectId))
    }

    suspend fun updateLesson(id: Long, newTitle: String) {
        val e = lessonDao.getById(id) ?: return
        lessonDao.update(e.copy(title = newTitle.trim()))
    }

    suspend fun deleteLesson(id: Long) {
        lessonDao.deleteById(id)
    }

    suspend fun setManualDue(id: Long, dueAt: Long) {
        val e = lessonDao.getById(id) ?: return
        lessonDao.update(e.copy(dueAt = dueAt, isManual = true))
    }

    suspend fun clearManualBackToAuto(id: Long) {
        val e = lessonDao.getById(id) ?: return
        val box = e.box.coerceIn(1, 5)
        val autoDue = System.currentTimeMillis() + daysForBox[box - 1] * dayMs
        lessonDao.update(e.copy(dueAt = autoDue, isManual = false))
    }

    suspend fun rate(id: Long, rating: Rating): Item? {
        val e = lessonDao.getById(id) ?: return null

        val newBox = when (rating) {
            Rating.AGAIN -> 1
            Rating.HARD -> e.box
            Rating.GOOD -> min(5, e.box + 1)
            Rating.EASY -> min(5, e.box + 2)
        }

        val newDue = System.currentTimeMillis() + daysForBox[newBox - 1] * dayMs
        val updated = e.copy(box = newBox, dueAt = newDue, isManual = false)
        lessonDao.update(updated)
        return updated.toItem()
    }

    suspend fun firstDueNow(): Item? =
        lessonDao.getFirstDue(System.currentTimeMillis())?.toItem()
}

class VmFactory(private val db: AppDatabase) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return Vm(db.lessonDao(), db.projectDao()) as T
    }
}

@Composable
fun App() {
    val nav = rememberNavController()
    val ctx = LocalContext.current
    val db = remember { AppDatabase.get(ctx) }
    val vm: Vm = viewModel(factory = VmFactory(db))

    val scope = rememberCoroutineScope()

    val items by vm.itemsFlow.collectAsState(initial = emptyList())

    val now = System.currentTimeMillis()
    val dueNowList = items.filter { it.state.dueAt <= now }
    val dueCount = dueNowList.size

    NavHost(nav, startDestination = "home") {

        composable("home") {
            Home(
                items = items,
                dueCount = dueCount,
                onAdd = { nav.navigate("add") },
                onOpen = { nav.navigate("review/$it") },
                onEdit = { nav.navigate("edit/$it") },
                onStartDue = {
                    val first = dueNowList.firstOrNull()
                    if (first != null) nav.navigate("review/${first.lesson.id}")
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
                    dueNowCount = dueCount,
                    onBack = { nav.popBackStack() },
                    onRate = { r ->
                        scope.launch {
                            val updated = vm.rate(id, r)
                            if (updated != null) {
                                NotificationScheduler.schedule(
                                    ctx,
                                    updated.lesson.id,
                                    updated.lesson.title,
                                    updated.state.dueAt
                                )
                            }

                            val next = vm.firstDueNow()
                            if (next != null) nav.navigate("review/${next.lesson.id}") { popUpTo("home") }
                            else nav.navigate("home") { popUpTo("home") { inclusive = true } }
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
                    initialTitle = item.lesson.title,
                    dueAt = item.state.dueAt,
                    isManual = item.state.isManual,
                    onBack = { nav.popBackStack() },

                    onSave = { newTitle ->
                        scope.launch {
                            vm.updateLesson(id, newTitle)
                            // dueAt didn’t change, but title might → reschedule with new title
                            NotificationScheduler.schedule(ctx, id, newTitle.trim(), item.state.dueAt)
                            nav.popBackStack()
                        }
                    },

                    onSetManualDue = { millis ->
                        scope.launch {
                            vm.setManualDue(id, millis)
                            NotificationScheduler.schedule(ctx, id, item.lesson.title, millis)
                        }
                    },

                    onClearManual = {
                        scope.launch {
                            vm.clearManualBackToAuto(id)
                            // after switching back to auto, the dueAt changes; simplest is to re-query:
                            val refreshed = vm.observeItem(id).firstOrNull()
                            if (refreshed != null) {
                                NotificationScheduler.schedule(ctx, id, refreshed.lesson.title, refreshed.state.dueAt)
                            }
                        }
                    },

                    onDelete = {
                        scope.launch {
                            NotificationScheduler.cancel(ctx, id)
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
        topBar = { TopAppBar(title = { Text("Lessons") }) },
        floatingActionButton = { FloatingActionButton(onClick = onAdd) { Text("+") } }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Due now: $dueCount", modifier = Modifier.weight(1f))
                    Button(onClick = onStartDue, enabled = dueCount > 0) { Text("Start") }
                }
            }

            if (items.isEmpty()) {
                Text("No lessons yet. Tap + to add one.")
            } else {
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
}

@Composable
fun Add(onBack: () -> Unit, onSave: (String) -> Unit) {
    var title by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add lesson") },
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
                label = { Text("Lesson title") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Button(
                onClick = { onSave(title) },
                enabled = title.trim().isNotEmpty()
            ) { Text("Save") }
        }
    }
}

@Composable
fun Review(item: Item, dueNowCount: Int, onBack: () -> Unit, onRate: (Rating) -> Unit) {
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
                    Text("Step ${item.state.step} • Next: ${formatDue(item.state.dueAt)}")
                    Text("Due queue: $dueNowCount")
                }
            }

            Button(onClick = { onRate(Rating.AGAIN) }, modifier = Modifier.fillMaxWidth()) {
                Text("Very hard – review tomorrow")
            }
            OutlinedButton(onClick = { onRate(Rating.HARD) }, modifier = Modifier.fillMaxWidth()) {
                Text("Hard – soon (2–3 days)")
            }
            Button(onClick = { onRate(Rating.GOOD) }, modifier = Modifier.fillMaxWidth()) {
                Text("Good – later (5+ days)")
            }
            Button(onClick = { onRate(Rating.EASY) }, modifier = Modifier.fillMaxWidth()) {
                Text("Very easy – much later")
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
                Text("Step ${item.state.step} • Next: ${formatDue(item.state.dueAt)}")
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit")
            }
        }
    }
}

@Composable
fun EditScreen(
    initialTitle: String,
    dueAt: Long,
    isManual: Boolean,
    onBack: () -> Unit,
    onSave: (String) -> Unit,
    onSetManualDue: (Long) -> Unit,
    onClearManual: () -> Unit,
    onDelete: () -> Unit
) {
    val ctx = LocalContext.current

    var title by remember { mutableStateOf(initialTitle) }
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Edit lesson") },
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
                label = { Text("Lesson title") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Button(
                onClick = { onSave(title) },
                enabled = title.trim().isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save changes") }

            // ---- Manual scheduling section ----
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Next review", style = MaterialTheme.typography.titleMedium)
                    Text(
                        (if (isManual) "Manual: " else "Auto: ") + formatDue(dueAt)
                    )

                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            // start with current dueAt as defaults
                            val zdt = Instant.ofEpochMilli(dueAt).atZone(ZoneId.systemDefault())
                            val initDate = zdt.toLocalDate()
                            val initTime = zdt.toLocalTime().withSecond(0).withNano(0)

                            DatePickerDialog(
                                ctx,
                                { _, y, m, d ->
                                    val pickedDate = LocalDate.of(y, m + 1, d)
                                    TimePickerDialog(
                                        ctx,
                                        { _, hh, mm ->
                                            val pickedTime = LocalTime.of(hh, mm)
                                            val ldt = LocalDateTime.of(pickedDate, pickedTime)
                                            val millis = ldt.atZone(ZoneId.systemDefault())
                                                .toInstant()
                                                .toEpochMilli()
                                            onSetManualDue(millis)
                                        },
                                        initTime.hour,
                                        initTime.minute,
                                        true
                                    ).show()
                                },
                                initDate.year,
                                initDate.monthValue - 1,
                                initDate.dayOfMonth
                            ).show()
                        }
                    ) {
                        Text("Set manual date & time")
                    }

                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onClearManual,
                        enabled = isManual
                    ) {
                        Text("Back to auto schedule")
                    }
                }
            }

            OutlinedButton(
                onClick = { confirmDelete = true },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Delete lesson") }

            if (confirmDelete) {
                AlertDialog(
                    onDismissRequest = { confirmDelete = false },
                    title = { Text("Delete?") },
                    text = { Text("This will remove the lesson.") },
                    confirmButton = {
                        TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Delete") }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
                    }
                )
            }
        }
    }
}


private val dueFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE, MMM d • HH:mm")

private fun formatDue(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(dueFormatter)
