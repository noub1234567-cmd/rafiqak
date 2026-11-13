@file:OptIn(ExperimentalMaterial3Api::class)

package app.rafiqak.dhikr

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainVm(private val app: Application) : AndroidViewModel(app) {
    private val dao = AppDb.get(app).dao()

    val reminders = dao.observeReminders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val doneToday = dao.observeCompletions(LocalDate.now().toEpochDay())
        .map { list -> list.map { it.reminderId }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    private val ticker = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(60_000)
        }
    }

    val missed = combine(
        dao.observeReminders(),
        dao.observeCompletionsFrom(LocalDate.now().toEpochDay() - 8),
        ticker
    ) { rs, cs, now -> computeMissed(rs, cs.map { it.reminderId to it.epochDay }.toSet(), now) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun markMissedDone(m: Missed) = viewModelScope.launch(Dispatchers.IO) {
        dao.complete(Completion(m.r.id, m.day, System.currentTimeMillis()))
        app.getSystemService(NotificationManager::class.java).cancel(m.r.id.toInt())
    }

    // إعادة التذكير لا تسجّل الإنجاز
    fun reRemind(m: Missed, hour: Int, minute: Int) {
        Scheduler.snoozeAt(app, m.r.id, Scheduler.nextClock(hour, minute), m.day)
    }

    fun save(r: Reminder) = viewModelScope.launch(Dispatchers.IO) {
        val id = if (r.id == 0L) dao.insert(r) else { dao.update(r); r.id }
        Scheduler.schedule(app, r.copy(id = id))
    }

    fun delete(r: Reminder) = viewModelScope.launch(Dispatchers.IO) {
        Scheduler.cancelAll(app, r.id)
        dao.deleteCompletions(r.id)
        dao.delete(r.id)
    }

    fun setEnabled(r: Reminder, enabled: Boolean) = save(r.copy(enabled = enabled))

    fun setDone(r: Reminder, done: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        val day = LocalDate.now().toEpochDay()
        if (done) {
            dao.complete(Completion(r.id, day, System.currentTimeMillis()))
            app.getSystemService(NotificationManager::class.java).cancel(r.id.toInt())
        } else {
            dao.uncomplete(r.id, day)
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifier.ensureChannels(this)
        setContent {
            AppTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(Modifier.fillMaxSize()) { Root() }
                }
            }
        }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFF6FCFB5))
    else lightColorScheme(primary = Color(0xFF1F6F5C))
    MaterialTheme(colorScheme = scheme, content = content)
}

fun fmtTime(h: Int, m: Int): String =
    LocalTime.of(h, m).format(DateTimeFormatter.ofPattern("h:mm a", Locale.forLanguageTag("ar")))

fun repeatText(r: Reminder): String = when (r.repeatType) {
    "DAILY" -> "يومياً"
    "ONCE" -> "مرة واحدة"
    else -> DAY_NAMES.filterIndexed { i, _ -> ((r.daysMask shr i) and 1) == 1 }.joinToString("، ")
}

@Composable
fun Root(vm: MainVm = viewModel()) {
    val ctx = LocalContext.current
    val reminders by vm.reminders.collectAsStateWithLifecycle()
    val done by vm.doneToday.collectAsStateWithLifecycle()
    val missed by vm.missed.collectAsStateWithLifecycle()
    var showMissed by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Reminder?>(null) }
    var creating by remember { mutableStateOf(false) }
    var canExact by remember { mutableStateOf(true) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
    }
    BackHandler(enabled = creating || editing != null || showMissed) {
        creating = false; editing = null; showMissed = false
    }

    if (showMissed) {
        MissedScreen(
            missed = missed,
            onDone = { vm.markMissedDone(it) },
            onReRemind = { m, h, min -> vm.reRemind(m, h, min) },
            onClose = { showMissed = false }
        )
    } else if (creating || editing != null) {
        EditScreen(
            initial = editing,
            onSave = { vm.save(it); creating = false; editing = null },
            onDelete = { editing?.let { r -> vm.delete(r) }; creating = false; editing = null },
            onClose = { creating = false; editing = null }
        )
    } else {
        Home(
            reminders = reminders, done = done, canExact = canExact,
            missedCount = missed.size, onOpenMissed = { showMissed = true },
            onAdd = { creating = true },
            onEdit = { editing = it },
            onToggleDone = { r, d -> vm.setDone(r, d) },
            onToggleEnabled = { r, e -> vm.setEnabled(r, e) }
        )
    }
}

@Composable
fun Home(
    reminders: List<Reminder>,
    done: Set<Long>,
    canExact: Boolean,
    missedCount: Int,
    onOpenMissed: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (Reminder) -> Unit,
    onToggleDone: (Reminder, Boolean) -> Unit,
    onToggleEnabled: (Reminder, Boolean) -> Unit
) {
    val ctx = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("رفيقك في الذكر") },
                actions = { TextButton(onClick = onOpenMissed) { Text("الفوائت ($missedCount)") } }
            )
        },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = onAdd) { Text("تذكير جديد") } }
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!canExact) item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("للحصول على تنبيهات في وقتها بدقة، اسمح للتطبيق بالمنبّهات الدقيقة.")
                        TextButton(onClick = {
                            if (Build.VERSION.SDK_INT >= 31) {
                                ctx.startActivity(
                                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}"))
                                )
                            }
                        }) { Text("فتح الإعدادات") }
                    }
                }
            }
            if (reminders.isEmpty()) item {
                Text("لا توجد تذكيرات بعد. أضف أول تذكير.", Modifier.padding(16.dp))
            }
            items(reminders, key = { it.id }) { r ->
                Card(onClick = { onEdit(r) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${r.category} · ${fmtTime(r.hour, r.minute)} · ${repeatText(r)}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Checkbox(checked = r.id in done, onCheckedChange = { onToggleDone(r, it) })
                            Text("تم اليوم", style = MaterialTheme.typography.labelSmall)
                        }
                        Switch(
                            checked = r.enabled,
                            onCheckedChange = { onToggleEnabled(r, it) },
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EditScreen(initial: Reminder?, onSave: (Reminder) -> Unit, onDelete: () -> Unit, onClose: () -> Unit) {
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var category by remember { mutableStateOf(initial?.category ?: CATEGORIES[0]) }
    var note by remember { mutableStateOf(initial?.note ?: "") }
    var hour by remember { mutableIntStateOf(initial?.hour ?: 7) }
    var minute by remember { mutableIntStateOf(initial?.minute ?: 0) }
    var repeat by remember { mutableStateOf(initial?.repeatType ?: "DAILY") }
    var mask by remember { mutableIntStateOf(initial?.daysMask ?: 0) }
    var onceDay by remember { mutableLongStateOf(initial?.onceEpochDay ?: LocalDate.now().toEpochDay()) }
    var sound by remember { mutableStateOf(initial?.sound ?: true) }
    var showTime by remember { mutableStateOf(false) }
    var showDate by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val needsDays = repeat == "WEEKLY" || repeat == "DAYS"
    val valid = title.isNotBlank() && (!needsDays || mask != 0)

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (initial == null) "تذكير جديد" else "تعديل التذكير") },
            navigationIcon = { TextButton(onClick = onClose) { Text("إلغاء") } }
        )
    }) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = title, onValueChange = { title = it },
                label = { Text("اسم العبادة") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Text("القسم")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(CATEGORIES) { c ->
                    FilterChip(selected = c == category, onClick = { category = c }, label = { Text(c) })
                }
            }
            OutlinedButton(onClick = { showTime = true }) { Text("الوقت: ${fmtTime(hour, minute)}") }

            Text("التكرار")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(REPEATS) { (key, label) ->
                    FilterChip(selected = repeat == key, onClick = {
                        repeat = key
                        if (key == "WEEKLY" && mask.countOneBits() > 1) mask = mask and -mask
                        if ((key == "WEEKLY" || key == "DAYS") && mask == 0) {
                            mask = 1 shl (LocalDate.now().dayOfWeek.value % 7)
                        }
                    }, label = { Text(label) })
                }
            }
            if (needsDays) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(DAY_NAMES.indices.toList()) { i ->
                        FilterChip(
                            selected = ((mask shr i) and 1) == 1,
                            onClick = { mask = if (repeat == "WEEKLY") (1 shl i) else mask xor (1 shl i) },
                            label = { Text(DAY_NAMES[i]) }
                        )
                    }
                }
            }
            if (repeat == "ONCE") {
                OutlinedButton(onClick = { showDate = true }) {
                    Text(
                        "التاريخ: " + LocalDate.ofEpochDay(onceDay)
                            .format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("ar")))
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("صوت تنبيه خفيف", Modifier.weight(1f))
                Switch(checked = sound, onCheckedChange = { sound = it })
            }
            OutlinedTextField(
                value = note, onValueChange = { note = it },
                label = { Text("وصف (اختياري)") }, modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = {
                    onSave(
                        Reminder(
                            id = initial?.id ?: 0L,
                            title = title.trim(), category = category, note = note.trim(),
                            hour = hour, minute = minute, repeatType = repeat,
                            daysMask = if (needsDays) mask else 0,
                            onceEpochDay = onceDay, sound = sound,
                            enabled = initial?.enabled ?: true,
                            createdAt = initial?.createdAt ?: System.currentTimeMillis()
                        )
                    )
                },
                enabled = valid, modifier = Modifier.fillMaxWidth()
            ) { Text("حفظ") }
            if (initial != null) {
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("حذف التذكير", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (showTime) {
        val st = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = false)
        AlertDialog(
            onDismissRequest = { showTime = false },
            confirmButton = {
                TextButton(onClick = { hour = st.hour; minute = st.minute; showTime = false }) { Text("تم") }
            },
            dismissButton = { TextButton(onClick = { showTime = false }) { Text("إلغاء") } },
            text = { TimePicker(state = st) }
        )
    }
    if (showDate) {
        val ds = rememberDatePickerState(initialSelectedDateMillis = onceDay * 86_400_000L)
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    ds.selectedDateMillis?.let { onceDay = it / 86_400_000L }
                    showDate = false
                }) { Text("تم") }
            }
        ) { DatePicker(state = ds) }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("حذف التذكير؟") },
            text = { Text("سيُحذف التذكير مع سجل إنجازاته.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("حذف") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("إلغاء") } }
        )
    }
}

fun dayLabel(epochDay: Long): String {
    val today = LocalDate.now().toEpochDay()
    return when (epochDay) {
        today -> "اليوم"
        today - 1 -> "أمس"
        else -> DAY_NAMES[LocalDate.ofEpochDay(epochDay).dayOfWeek.value % 7]
    }
}

@Composable
fun MissedScreen(
    missed: List<Missed>,
    onDone: (Missed) -> Unit,
    onReRemind: (Missed, Int, Int) -> Unit,
    onClose: () -> Unit
) {
    var target by remember { mutableStateOf<Missed?>(null) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("العبادات الفائتة") },
            navigationIcon = { TextButton(onClick = onClose) { Text("رجوع") } }
        )
    }) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (missed.isEmpty()) item { Text("لا توجد عبادات فائتة.", Modifier.padding(16.dp)) }
            items(missed, key = { "${it.r.id}-${it.day}" }) { m ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(m.r.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${m.r.category} · ${dayLabel(m.day)} · ${fmtTime(m.r.hour, m.r.minute)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onDone(m) }) { Text("تم") }
                            OutlinedButton(onClick = { target = m }) { Text("إعادة التذكير") }
                        }
                    }
                }
            }
        }
    }
    target?.let { m ->
        val later = LocalTime.now().plusMinutes(30)
        val st = rememberTimePickerState(initialHour = later.hour, initialMinute = later.minute, is24Hour = false)
        AlertDialog(
            onDismissRequest = { target = null },
            confirmButton = {
                TextButton(onClick = { onReRemind(m, st.hour, st.minute); target = null }) { Text("تذكيري") }
            },
            dismissButton = { TextButton(onClick = { target = null }) { Text("إلغاء") } },
            text = { TimePicker(state = st) }
        )
    }
}
