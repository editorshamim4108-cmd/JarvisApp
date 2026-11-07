package com.jarvis.app.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "reminders")
data class Reminder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "trigger_time") val triggerTimeMillis: Long,
    @ColumnInfo(name = "is_acknowledged") val isAcknowledged: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface ReminderDao {
    @Insert
    suspend fun insert(reminder: Reminder): Long

    @Query("SELECT * FROM reminders WHERE is_acknowledged = 0 ORDER BY trigger_time ASC")
    suspend fun getActiveReminders(): List<Reminder>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun getById(id: Long): Reminder?

    @Query("UPDATE reminders SET is_acknowledged = 1 WHERE id = :id")
    suspend fun markAcknowledged(id: Long)

    @Query("DELETE FROM reminders WHERE is_acknowledged = 1")
    suspend fun deleteAcknowledged()

    @Query("SELECT COUNT(*) FROM reminders WHERE is_acknowledged = 0")
    suspend fun getActiveCount(): Int
}
