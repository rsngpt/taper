package dev.taper.queue

import android.content.Context
import androidx.room.Room
import dev.taper.classify.AgentFailure
import dev.taper.classify.ExceptionClassifier
import dev.taper.classify.FailureCategory
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * All pending updates for one conversation, coalesced into a single sync unit.
 * [updates] is ordered oldest-first (insert order), so a syncer can replay or
 * merge them deterministically.
 */
data class CoalescedBatch(
    val conversationId: String,
    val updates: List<PendingUpdate>,
) {
    val payloads: List<String> get() = updates.map { it.payload }
}

/**
 * Performs the actual network sync of one coalesced batch.
 * Throw to signal failure; wrap HTTP-level failures in [SyncHttpException] so
 * the classifier can see the status code and error body.
 */
fun interface Syncer {
    suspend fun sync(batch: CoalescedBatch)
}

/** Carries HTTP failure details from a [Syncer] to the classifier. */
class SyncHttpException(
    val status: Int,
    val errorBody: String? = null,
    message: String = "HTTP $status",
    cause: Throwable? = null,
) : Exception(message, cause)

/** Outcome of one [SyncQueue.drain] pass. */
data class DrainReport(
    val conversationsSynced: Int = 0,
    val updatesSynced: Int = 0,
    /** Conversations that failed transiently; their updates remain PENDING. */
    val transientFailures: Int = 0,
    /** Conversations that failed semantically; their updates moved to DEAD. */
    val semanticFailures: Int = 0,
)

/**
 * Offline-safe, SQLite-backed queue for agent-context updates.
 *
 * Guarantees:
 *  - **Durability**: rows live in a named Room database; process death with
 *    pending items loses nothing (see `SyncQueueProcessDeathTest`).
 *  - **Coalescing**: [drain] groups all pending updates per conversation and
 *    hands the [Syncer] ONE batch per conversation, instead of one request per
 *    update — reconnecting after a long offline stretch produces one sync
 *    transaction per conversation, not a request flood.
 *  - **At-least-once**: rows are deleted only after the syncer returns
 *    successfully. A crash between sync and delete re-delivers the batch, so
 *    syncers should be idempotent (e.g. key on update ids).
 *  - **No poison-pill loops**: failures run through [ExceptionClassifier];
 *    SEMANTIC failures are dead-lettered immediately, TRANSIENT ones stay
 *    pending until [maxAttempts].
 */
class SyncQueue internal constructor(
    private val dao: PendingUpdateDao,
    private val classifier: ExceptionClassifier,
    private val maxAttempts: Int,
    private val clock: () -> Long,
) {
    private val drainMutex = Mutex()

    /** Persists one update. Safe to call while offline; that is the point. */
    suspend fun enqueue(conversationId: String, payload: String): Long {
        require(conversationId.isNotBlank()) { "conversationId must not be blank" }
        return dao.insert(
            PendingUpdate(
                conversationId = conversationId,
                payload = payload,
                createdAtMs = clock(),
            ),
        )
    }

    suspend fun pending(): List<PendingUpdate> = dao.pending()

    suspend fun pendingCount(): Int = dao.pendingCount()

    /** Updates that permanently failed; kept for inspection until purged. */
    suspend fun deadLetters(): List<PendingUpdate> = dao.deadLetters()

    suspend fun purgeDeadLetters(): Int = dao.purgeDeadLetters()

    /**
     * Attempts to sync everything pending, one coalesced batch per conversation.
     * Re-entrant calls serialise on a mutex, so a connectivity-triggered drain
     * and a manual drain cannot double-send.
     */
    suspend fun drain(syncer: Syncer): DrainReport = drainMutex.withLock {
        var report = DrainReport()
        // Group in insert order: LinkedHashMap keeps first-seen conversation order,
        // and rows arrive ordered by id, so each batch is oldest-first.
        val byConversation = dao.pending().groupBy { it.conversationId }
        for ((conversationId, updates) in byConversation) {
            val ids = updates.map { it.id }
            try {
                syncer.sync(CoalescedBatch(conversationId, updates))
                dao.deleteAll(ids)
                report = report.copy(
                    conversationsSynced = report.conversationsSynced + 1,
                    updatesSynced = report.updatesSynced + updates.size,
                )
            } catch (e: Exception) {
                report = when (classify(e)) {
                    FailureCategory.SEMANTIC -> {
                        dao.markDead(ids)
                        report.copy(semanticFailures = report.semanticFailures + 1)
                    }
                    FailureCategory.TRANSIENT -> {
                        // Attempt cap turns a permanently-flaky batch into a dead
                        // letter instead of retrying forever.
                        val exhausted = updates.any { it.attemptCount + 1 >= maxAttempts }
                        if (exhausted) {
                            dao.markDead(ids)
                            report.copy(semanticFailures = report.semanticFailures + 1)
                        } else {
                            dao.recordAttempt(ids)
                            report.copy(transientFailures = report.transientFailures + 1)
                        }
                    }
                }
            }
        }
        report
    }

    private fun classify(e: Exception): FailureCategory {
        val failure = when (e) {
            is SyncHttpException -> AgentFailure(
                httpStatus = e.status,
                exception = e,
                responseBody = e.errorBody,
            )
            else -> AgentFailure(exception = e)
        }
        return classifier.classify(failure)
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 10
        internal const val DATABASE_NAME = "taper-sync-queue.db"

        /**
         * Opens (or creates) the durable queue backed by a named on-disk database.
         * One instance per process is recommended.
         */
        fun create(
            context: Context,
            classifier: ExceptionClassifier = ExceptionClassifier(),
            maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
        ): SyncQueue {
            val db = Room.databaseBuilder(
                context.applicationContext,
                TaperDatabase::class.java,
                DATABASE_NAME,
            ).build()
            return SyncQueue(db.pendingUpdates(), classifier, maxAttempts, System::currentTimeMillis)
        }
    }
}
