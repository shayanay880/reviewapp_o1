@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.v12
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.ui.platform.LocalContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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

class Vm : ViewModel() {

    private val daysForStep = listOf(1, 3, 5, 10, 20)
    private val dayMs = 24L * 60L * 60L * 1000L

    private var nextId = 1L
    private val _items = mutableStateListOf<Item>()
    val items: List<Item> get() = _items

    init {
        addLesson("CXR interpretation")
        addLesson("ECG basics")
    }

    fun addLesson(title: String) {
        val lesson = Lesson(nextId++, title.trim())
        _items.add(Item(lesson, State(lesson.id)))
        _items.sortBy { it.state.dueAt }
    }

    fun get(id: Long) = _items.firstOrNull { it.lesson.id == id }

    fun dueNow(now: Long = System.currentTimeMillis()) =
        _items.filter { it.state.dueAt <= now }.sortedBy { it.state.dueAt }

    fun setManualDue(id: Long, dueAt: Long) {
        val idx = _items.indexOfFirst { it.lesson.id == id }
        if (idx == -1) return
        val old = _items[idx]
        _items[idx] = old.copy(state = old.state.copy(dueAt = dueAt, isManual = true))
        _items.sortBy { it.state.dueAt }
    }

    fun clearManualBackToAuto(id: Long) {
        val idx = _items.indexOfFirst { it.lesson.id == id }
        if (idx == -1) return

        val old = _items[idx]
        val step = old.state.step.coerceIn(1, 5)
        val autoDue = System.currentTimeMillis() + daysForStep[step - 1] * dayMs

        _items[idx] = old.copy(state = old.state.copy(dueAt = autoDue, isManual = false))
        _items.sortBy { it.state.dueAt }
    }

    fun rate(id: Long, rating: Rating) {
        val index = _items.indexOfFirst { it.lesson.id == id }
        if (index == -1) return

        val old = _items[index]

        val newStep = when (rating) {
            Rating.AGAIN -> 1
            Rating.HARD -> old.state.step
            Rating.GOOD -> min(5, old.state.step + 1)
            Rating.EASY -> min(5, old.state.step + 2)
        }

        val newDue = System.currentTimeMillis() + daysForStep[newStep - 1] * dayMs

        _items[index] = old.copy(
            state = old.state.copy(step = newStep, dueAt = newDue, isManual = false)
        )
        _items.sortBy { it.state.dueAt }
    }

    fun updateLesson(id: Long, newTitle: String) {
        val idx = _items.indexOfFirst { it.lesson.id == id }
        if (idx == -1) return
        val old = _items[idx]
        _items[idx] = old.copy(lesson = old.lesson.copy(title = newTitle.trim()))
    }

    fun deleteLesson(id: Long) {
        val idx = _items.indexOfFirst { it.lesson.id == id }
        if (idx == -1) return
        _items.removeAt(idx)
    }
}


@Composable
fun App(vm: Vm = viewModel()) {
    val nav = rememberNavController()

    NavHost(nav, startDestination = "home") {

        composable("home") {
            Home(
                items = vm.items,
                dueCount = vm.dueNow().size,
                onAdd = { nav.navigate("add") },
                onOpen = { nav.navigate("review/$it") },
                onEdit = { nav.navigate("edit/$it") },
                onStartDue = {
                    val first = vm.dueNow().firstOrNull()
                    if (first != null) nav.navigate("review/${first.lesson.id}")
                }
            )
        }

        composable("add") {
            Add(
                onBack = { nav.popBackStack() },
                onSave = {
                    vm.addLesson(it)
                    nav.popBackStack()
                }
            )
        }

        composable(
            "review/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry ->
            val id = entry.arguments?.getLong("id") ?: 0L
            val item = vm.get(id)
            val ctx = LocalContext.current
            if (item == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Not found") }
            } else {
                Review(
                    item = item,
                    dueNowCount = vm.dueNow().size,
                    onBack = { nav.popBackStack() },
                    onRate = { r ->
                        vm.rate(id, r)
                        vm.get(id)?.let { updated ->
                            NotificationScheduler.schedule(ctx, updated.lesson.id, updated.lesson.title, updated.state.dueAt)
                        }
                        val next = vm.dueNow().firstOrNull()
                        if (next != null) nav.navigate("review/${next.lesson.id}") { popUpTo("home") }
                        else nav.navigate("home") { popUpTo("home") { inclusive = true } }
                    }
                )
            }
        }

        composable(
            "edit/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType })
        ) { entry ->
            val id = entry.arguments?.getLong("id") ?: 0L
            val item = vm.get(id)
            val ctx = LocalContext.current
            if (item == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Not found") }
            } else {
                EditScreen(
                    initialTitle = item.lesson.title,
                    dueAt = item.state.dueAt,
                    isManual = item.state.isManual,
                    onBack = { nav.popBackStack() },
                    onSave = { newTitle ->
                        vm.updateLesson(id, newTitle)
                        vm.get(id)?.let { updated ->
                            // update alarm text too
                            NotificationScheduler.schedule(ctx, updated.lesson.id, updated.lesson.title, updated.state.dueAt)
                        }
                        nav.popBackStack()
                    },
                    onSetManualDue = { millis ->
                        vm.setManualDue(id, millis)
                        vm.get(id)?.let { updated ->
                            NotificationScheduler.schedule(ctx, updated.lesson.id, updated.lesson.title, updated.state.dueAt)
                        }
                    },
                    onClearManual = {
                        vm.clearManualBackToAuto(id)
                        vm.get(id)?.let { updated ->
                            NotificationScheduler.schedule(ctx, updated.lesson.id, updated.lesson.title, updated.state.dueAt)
                        }
                    },
                    onDelete = {
                        NotificationScheduler.cancel(ctx, id)
                        vm.deleteLesson(id)
                        nav.navigate("home") { popUpTo("home") { inclusive = true } }
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
