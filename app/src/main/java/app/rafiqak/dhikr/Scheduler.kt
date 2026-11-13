package app.rafiqak.dhikr

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

object Scheduler {
    const val ACTION_SNOOZE_ALARM = "app.rafiqak.dhikr.SNOOZE_ALARM"
    const val ACTION_CHECK = "app.rafiqak.dhikr.CHECK"

    // بعد كم دقيقة من الموعد نُرسل إشعار «لم تُنجز بعد» إن لم يؤكد المستخدم
    const val CHECK_DELAY_MIN = 60

    private const val KIND_MAIN = 1
    private const val KIND_SNOOZE = 2
    private const val KIND_CHECK = 6

    fun matches(r: Reminder, d: LocalDate): Boolean =
        r.repeatType == "DAILY" || ((r.daysMask shr (d.dayOfWeek.value % 7)) and 1) == 1

    fun nextTime(r: Reminder, after: Long = System.currentTimeMillis()): Long? {
        val zone = ZoneId.systemDefault()
        val time = LocalTime.of(r.hour, r.minute)
        if (r.repeatType == "ONCE") {
            val t = LocalDate.ofEpochDay(r.onceEpochDay).atTime(time).atZone(zone).toInstant().toEpochMilli()
            return if (t > after) t else null
        }
        val today = Instant.ofEpochMilli(after).atZone(zone).toLocalDate()
        for (i in 0..7) {
            val d = today.plusDays(i.toLong())
            if (!matches(r, d)) continue
            val t = d.atTime(time).atZone(zone).toInstant().toEpochMilli()
            if (t > after) return t
        }
        return null
    }

    /** أقرب لحظة بهذه الساعة والدقيقة (اليوم إن لم تفت، وإلا غداً). */
    fun nextClock(hour: Int, minute: Int, now: Long = System.currentTimeMillis()): Long {
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val t = today.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
        return if (t > now) t
        else today.plusDays(1).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
    }

    private fun pending(c: Context, id: Long, kind: Int, day: Long = -1L): PendingIntent {
        val i = Intent(c, AlarmReceiver::class.java).putExtra("id", id).putExtra("day", day)
        when (kind) {
            KIND_SNOOZE -> i.action = ACTION_SNOOZE_ALARM
            KIND_CHECK -> i.action = ACTION_CHECK
        }
        return PendingIntent.getBroadcast(
            c, id.toInt() * 10 + kind, i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun setAlarm(c: Context, at: Long, pi: PendingIntent) {
        val am = c.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun schedule(c: Context, r: Reminder) {
        cancel(c, r.id)
        if (!r.enabled) return
        nextTime(r)?.let { setAlarm(c, it, pending(c, r.id, KIND_MAIN)) }
    }

    fun cancel(c: Context, id: Long) {
        val am = c.getSystemService(AlarmManager::class.java)
        am.cancel(pending(c, id, KIND_MAIN))
        am.cancel(pending(c, id, KIND_SNOOZE))
    }

    fun cancelAll(c: Context, id: Long) {
        cancel(c, id)
        c.getSystemService(AlarmManager::class.java).cancel(pending(c, id, KIND_CHECK))
    }

    fun snoozeAt(c: Context, id: Long, at: Long, day: Long = -1L) {
        setAlarm(c, at, pending(c, id, KIND_SNOOZE, day))
    }

    fun snooze(c: Context, id: Long, minutes: Int, day: Long = -1L) {
        snoozeAt(c, id, System.currentTimeMillis() + minutes * 60_000L, day)
    }

    fun scheduleCheck(c: Context, id: Long, day: Long) {
        setAlarm(c, System.currentTimeMillis() + CHECK_DELAY_MIN * 60_000L, pending(c, id, KIND_CHECK, day))
    }

    suspend fun rescheduleAll(c: Context) {
        AppDb.get(c).dao().allReminders().forEach { schedule(c, it) }
    }
}

object Notifier {
    private const val CH_SOUND = "reminders_sound"
    private const val CH_QUIET = "reminders_quiet"

    fun ensureChannels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SOUND, "تذكيرات بصوت خفيف", NotificationManager.IMPORTANCE_DEFAULT)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_QUIET, "تذكيرات بدون صوت", NotificationManager.IMPORTANCE_LOW)
        )
    }

    fun show(c: Context, r: Reminder, day: Long, late: Boolean) {
        ensureChannels(c)
        val nm = c.getSystemService(NotificationManager::class.java)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        fun act(action: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
            c, r.id.toInt() * 10 + code,
            Intent(c, ActionReceiver::class.java).setAction(action).putExtra("id", r.id).putExtra("day", day),
            flags
        )
        val open = PendingIntent.getActivity(c, r.id.toInt() * 10 + 5, Intent(c, MainActivity::class.java), flags)
        val text = if (late) "لم تُنجز بعد" else r.note.ifBlank { r.category }
        val n = Notification.Builder(c, if (r.sound) CH_SOUND else CH_QUIET)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(r.title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "تم", act(ActionReceiver.ACTION_DONE, 3))
            .addAction(0, "تأجيل ١٠ دقائق", act(ActionReceiver.ACTION_SNOOZE, 4))
            .build()
        if (nm.areNotificationsEnabled()) nm.notify(r.id.toInt(), n)
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("id", -1L)
        if (id < 0) return
        val extraDay = intent.getLongExtra("day", -1L)
        val isCheck = intent.action == Scheduler.ACTION_CHECK
        val app = context.applicationContext
        val pr = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDb.get(app).dao()
                val r = dao.reminder(id)
                if (r != null && r.enabled) {
                    val today = LocalDate.now().toEpochDay()
                    val day = if (extraDay >= 0) extraDay else today
                    if (isCheck) {
                        // لا نعتبرها منجزة: إن لم يؤكد المستخدم نذكّره مرة واحدة أنها لم تُنجز بعد
                        if (dao.doneCount(id, day) == 0) Notifier.show(app, r, day, true)
                    } else {
                        Notifier.show(app, r, day, false)
                        Scheduler.schedule(app, r)
                        if (day == today) Scheduler.scheduleCheck(app, id, day)
                    }
                }
            } finally {
                pr.finish()
            }
        }
    }
}

class ActionReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_DONE = "app.rafiqak.dhikr.DONE"
        const val ACTION_SNOOZE = "app.rafiqak.dhikr.SNOOZE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("id", -1L)
        if (id < 0) return
        val extraDay = intent.getLongExtra("day", -1L)
        val app = context.applicationContext
        val action = intent.action
        val pr = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val day = if (extraDay >= 0) extraDay else LocalDate.now().toEpochDay()
                app.getSystemService(NotificationManager::class.java).cancel(id.toInt())
                when (action) {
                    ACTION_DONE -> AppDb.get(app).dao()
                        .complete(Completion(id, day, System.currentTimeMillis()))
                    ACTION_SNOOZE -> Scheduler.snooze(app, id, 10, day)
                }
            } finally {
                pr.finish()
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pr = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Scheduler.rescheduleAll(app)
            } finally {
                pr.finish()
            }
        }
    }
}
