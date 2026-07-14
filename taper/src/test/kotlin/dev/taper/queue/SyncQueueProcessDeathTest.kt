package dev.taper.queue

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.taper.classify.ExceptionClassifier
import java.net.SocketTimeoutException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "App killed while the queue has pending items."
 *
 * Uses a NAMED on-disk Room database (exactly what [SyncQueue.create] uses in
 * production, as opposed to the in-memory database of the behaviour tests) and
 * simulates process death by closing every connection and reopening the same
 * file with a brand-new Room instance: the only state that survives is what
 * SQLite durably persisted, which is precisely the process-death contract.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncQueueProcessDeathTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val openDbs = mutableListOf<TaperDatabase>()

    private fun openQueue(): Pair<SyncQueue, TaperDatabase> {
        val db = Room.databaseBuilder(context, TaperDatabase::class.java, SyncQueue.DATABASE_NAME)
            .build()
        openDbs += db
        val queue = SyncQueue(
            dao = db.pendingUpdates(),
            classifier = ExceptionClassifier(),
            maxAttempts = 10,
            clock = System::currentTimeMillis,
        )
        return queue to db
    }

    @After
    fun tearDown() {
        openDbs.forEach { runCatching { it.close() } }
        context.deleteDatabase(SyncQueue.DATABASE_NAME)
    }

    @Test
    fun `pending updates survive process death`() = runBlocking<Unit> {
        val (firstLife, firstDb) = openQueue()
        firstLife.enqueue("conv-a", "a1")
        firstLife.enqueue("conv-b", "b1")
        firstLife.enqueue("conv-a", "a2")
        firstDb.close() // process dies here

        val (secondLife, _) = openQueue()
        assertThat(secondLife.pendingCount()).isEqualTo(3)

        val syncer = mutableListOf<CoalescedBatch>()
        val report = secondLife.drain { syncer += it }
        assertThat(report.updatesSynced).isEqualTo(3)
        assertThat(syncer.map { it.conversationId }).containsExactly("conv-a", "conv-b")
        assertThat(syncer.first { it.conversationId == "conv-a" }.payloads)
            .containsExactly("a1", "a2").inOrder()
    }

    @Test
    fun `attempt counts survive process death`() = runBlocking<Unit> {
        val (firstLife, firstDb) = openQueue()
        firstLife.enqueue("conv-a", "a1")
        firstLife.drain { throw SocketTimeoutException("offline") }
        firstLife.drain { throw SocketTimeoutException("still offline") }
        firstDb.close() // process dies here

        val (secondLife, _) = openQueue()
        assertThat(secondLife.pending().single().attemptCount).isEqualTo(2)
    }

    @Test
    fun `dead letters survive process death`() = runBlocking<Unit> {
        val (firstLife, firstDb) = openQueue()
        firstLife.enqueue("conv-a", "poison")
        firstLife.drain { throw SyncHttpException(status = 422) }
        firstDb.close() // process dies here

        val (secondLife, _) = openQueue()
        assertThat(secondLife.pendingCount()).isEqualTo(0)
        assertThat(secondLife.deadLetters().single().payload).isEqualTo("poison")
    }

    @Test
    fun `synced updates are gone after process death - no double delivery on restart`() = runBlocking<Unit> {
        val (firstLife, firstDb) = openQueue()
        firstLife.enqueue("conv-a", "a1")
        firstLife.drain { /* success */ }
        firstDb.close() // process dies here

        val (secondLife, _) = openQueue()
        assertThat(secondLife.pendingCount()).isEqualTo(0)
        val syncer = mutableListOf<CoalescedBatch>()
        secondLife.drain { syncer += it }
        assertThat(syncer).isEmpty()
    }
}
