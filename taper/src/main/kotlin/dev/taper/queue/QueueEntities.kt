package dev.taper.queue

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

/**
 * One queued agent-context update awaiting sync. Persisted in SQLite (via Room)
 * so the queue survives process death — an app killed with pending items picks
 * them up on next start.
 */
@Entity(
    tableName = "taper_pending_updates",
    indices = [Index("conversationId"), Index("status")],
)
data class PendingUpdate(
    /** Monotonic insert order; coalescing preserves it within a conversation. */
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: String,
    /** Opaque caller payload (typically JSON). Taper never interprets it. */
    val payload: String,
    val createdAtMs: Long,
    @ColumnInfo(defaultValue = "0") val attemptCount: Int = 0,
    @ColumnInfo(defaultValue = "PENDING") val status: Status = Status.PENDING,
) {
    enum class Status {
        /** Waiting to be synced. */
        PENDING,

        /**
         * Permanently failed: classified SEMANTIC, or exceeded the attempt cap.
         * Kept for inspection (dead letter) rather than silently deleted;
         * callers purge explicitly.
         */
        DEAD,
    }
}

@Dao
internal interface PendingUpdateDao {
    @Insert
    suspend fun insert(update: PendingUpdate): Long

    @Query("SELECT * FROM taper_pending_updates WHERE status = 'PENDING' ORDER BY id ASC")
    suspend fun pending(): List<PendingUpdate>

    @Query("SELECT COUNT(*) FROM taper_pending_updates WHERE status = 'PENDING'")
    suspend fun pendingCount(): Int

    @Query("SELECT * FROM taper_pending_updates WHERE status = 'DEAD' ORDER BY id ASC")
    suspend fun deadLetters(): List<PendingUpdate>

    /** All-or-nothing removal of a synced batch; runs as a single SQL statement. */
    @Query("DELETE FROM taper_pending_updates WHERE id IN (:ids)")
    suspend fun deleteAll(ids: List<Long>): Int

    @Query("UPDATE taper_pending_updates SET attemptCount = attemptCount + 1 WHERE id IN (:ids)")
    suspend fun recordAttempt(ids: List<Long>)

    @Query("UPDATE taper_pending_updates SET status = 'DEAD', attemptCount = attemptCount + 1 WHERE id IN (:ids)")
    suspend fun markDead(ids: List<Long>)

    @Query("DELETE FROM taper_pending_updates WHERE status = 'DEAD'")
    suspend fun purgeDeadLetters(): Int
}

@Database(entities = [PendingUpdate::class], version = 1, exportSchema = false)
@androidx.room.TypeConverters(StatusConverter::class)
internal abstract class TaperDatabase : RoomDatabase() {
    abstract fun pendingUpdates(): PendingUpdateDao
}

internal class StatusConverter {
    @androidx.room.TypeConverter
    fun fromStatus(status: PendingUpdate.Status): String = status.name

    @androidx.room.TypeConverter
    fun toStatus(raw: String): PendingUpdate.Status = PendingUpdate.Status.valueOf(raw)
}
