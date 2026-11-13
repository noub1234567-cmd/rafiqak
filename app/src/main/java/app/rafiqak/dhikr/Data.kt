package app.rafiqak.dhikr

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

val CATEGORIES = listOf(
    "أذكار", "قرآن", "حفظ ومراجعة", "صلاة", "صيام", "دعاء",
    "استغفار وتسبيح", "صدقة وصلة رحم", "طلب العلم الشرعي", "أخرى"
)

// الترتيب: الأحد = 0 ... السبت = 6
val DAY_NAMES = listOf("الأحد", "الاثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت")

val REPEATS = listOf("DAILY" to "يومياً", "WEEKLY" to "أسبوعياً", "DAYS" to "أيام محددة", "ONCE" to "مرة واحدة")

@Entity(tableName = "reminders")
data class Reminder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val category: String,
    val note: String = "",
    val hour: Int,
    val minute: Int,
    val repeatType: String,      // DAILY | WEEKLY | DAYS | ONCE
    val daysMask: Int = 0,       // بت لكل يوم (الأحد = البت 0)
    val onceEpochDay: Long = 0,
    val sound: Boolean = true,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

// لا تُعتبر العبادة منجزة إلا بوجود سجل هنا (تأكيد المستخدم)
@Entity(tableName = "completions", primaryKeys = ["reminderId", "epochDay"])
data class Completion(val reminderId: Long, val epochDay: Long, val doneAt: Long)

@Dao
interface AppDao {
    @Query("SELECT * FROM reminders ORDER BY hour, minute")
    fun observeReminders(): Flow<List<Reminder>>

    @Query("SELECT * FROM reminders")
    suspend fun allReminders(): List<Reminder>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun reminder(id: Long): Reminder?

    @Insert
    suspend fun insert(r: Reminder): Long

    @Update
    suspend fun update(r: Reminder)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun complete(c: Completion)

    @Query("DELETE FROM completions WHERE reminderId = :id AND epochDay = :day")
    suspend fun uncomplete(id: Long, day: Long)

    @Query("DELETE FROM completions WHERE reminderId = :id")
    suspend fun deleteCompletions(id: Long)

    @Query("SELECT * FROM completions WHERE epochDay >= :from")
    fun observeCompletionsFrom(from: Long): Flow<List<Completion>>

    @Query("SELECT COUNT(*) FROM completions WHERE reminderId = :id AND epochDay = :day")
    suspend fun doneCount(id: Long, day: Long): Int

    @Query("SELECT * FROM completions WHERE epochDay = :day")
    fun observeCompletions(day: Long): Flow<List<Completion>>
}

@Database(entities = [Reminder::class, Completion::class], version = 2, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        @Volatile private var instance: AppDb? = null
        fun get(c: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(c.applicationContext, AppDb::class.java, "rafiqak.db")
                .addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE reminders ADD COLUMN createdAt INTEGER NOT NULL DEFAULT ${System.currentTimeMillis()}")
    }
}

/** عبادة فاتت: التذكير + اليوم الذي فاتت فيه. لا تزول إلا بتأكيد المستخدم. */
data class Missed(val r: Reminder, val day: Long)

const val MISSED_WINDOW_DAYS = 7

fun computeMissed(rs: List<Reminder>, done: Set<Pair<Long, Long>>, now: Long): List<Missed> {
    val zone = ZoneId.systemDefault()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val out = mutableListOf<Missed>()
    for (r in rs) {
        if (!r.enabled) continue
        for (k in 0 until MISSED_WINDOW_DAYS) {
            val d = today.minusDays(k.toLong())
            val occurs = if (r.repeatType == "ONCE") d.toEpochDay() == r.onceEpochDay else Scheduler.matches(r, d)
            if (!occurs) continue
            val t = d.atTime(LocalTime.of(r.hour, r.minute)).atZone(zone).toInstant().toEpochMilli()
            if (t > now || t < r.createdAt) continue
            if ((r.id to d.toEpochDay()) in done) continue
            out += Missed(r, d.toEpochDay())
        }
    }
    return out.sortedWith(compareByDescending<Missed> { it.day }.thenBy { it.r.hour * 60 + it.r.minute })
}
