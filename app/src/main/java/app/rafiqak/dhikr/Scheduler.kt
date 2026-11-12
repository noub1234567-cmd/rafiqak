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

    private fun matches(r: Reminder, d: LocalDate): Boolean =
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

    private fun pending(c: Context, id: Long, snooze: Boolean): PendingIntent {
        val i = Intent(c, AlarmReceiver::class.java).putExtra("id", id)
        if (snooze) i.action = ACTION_SNOOZE_ALARM
        val code = id.toInt() * 10 + (if (snooze) 2 else 1)
        return PendingIntent.getBroadcast(c, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
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
        nextTime(r)?.let { setAlarm(c, it, pending(c, r.id, false)) }
    }

    fun cancel(c: Context, id: Long) {
        val am = c.getSystemService(AlarmManager::class.java)
        am.cancel(pending(c, id, false))
        am.cancel(pending(c, id, true))
    }

    fun snooze(c: Context, id: Long, minutes: Int) {
        setAlarm(c, System.currentTimeMillis() + minutes * 60_000L, pending(c, id, true))
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

    fun show(c: Context, r: Reminder) {
        ensureChannels(c)
        val nm = c.getSystemService(NotificationManager::class.java)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        fun act(action: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
            c, r.id.toInt() * 10 + code,
            Intent(c, ActionReceiver::class.java).setAction(action).putExtra("id", r.id), flags
        )
        val open = PendingIntent.getActivity(c, r.id.toInt() * 10 + 5, Intent(c, MainActivity::class.java), flags)
        val n = Notification.Builder(c, if (r.sound) CH_SOUND else CH_QUIET)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(r.title)
            .setContentText(r.note.ifBlank { r.category })
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
        val app = context.applicationContext
        val pr = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val r = AppDb.get(app).dao().reminder(id)
                if (r != null && r.enabled) {
                    Notifier.show(app, r)
                    Scheduler.schedule(app, r)
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
        val app = context.applicationContext
        val action = intent.action
        val pr = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                app.getSystemService(NotificationManager::class.java).cancel(id.toInt())
                when (action) {
                    ACTION_DONE -> AppDb.get(app).dao()
                        .complete(Completion(id, LocalDate.now().toEpochDay(), System.currentTimeMillis()))
                    ACTION_SNOOZE -> Scheduler.snooze(app, id, 10)
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
