package dev.taper.queue

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.taper.classify.ExceptionClassifier
import java.net.SocketTimeoutException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SyncQueueTest {

    private lateinit var db: TaperDatabase
    private lateinit var queue: SyncQueue

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, TaperDatabase::class.java).build()
        queue = SyncQueue(
            dao = db.pendingUpdates(),
            classifier = ExceptionClassifier(),
            maxAttempts = 3,
            clock = { 1_000L },
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** Records every batch it receives; fails according to [failWith]. */
    private class RecordingSyncer(private val failWith: (CoalescedBatch) -> Exception? = { null }) : Syncer {
        val batches = mutableListOf<CoalescedBatch>()
        override suspend fun sync(batch: CoalescedBatch) {
            batches += batch
            failWith(batch)?.let { throw it }
        }
    }

    @Test
    fun `enqueue persists and preserves order`() = runBlocking<Unit> {
        queue.enqueue("conv-a", """{"delta": 1}""")
        queue.enqueue("conv-a", """{"delta": 2}""")
        assertThat(queue.pendingCount()).isEqualTo(2)
        assertThat(queue.pending().map { it.payload })
            .containsExactly("""{"delta": 1}""", """{"delta": 2}""").inOrder()
    }

    @Test
    fun `blank conversation id is rejected`() = runBlocking<Unit> {
        try {
            queue.enqueue("  ", "{}")
            error("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertThat(queue.pendingCount()).isEqualTo(0)
        }
    }

    @Test
    fun `drain coalesces per conversation - one syncer call per conversation`() = runBlocking<Unit> {
        queue.enqueue("conv-a", "a1")
        queue.enqueue("conv-b", "b1")
        queue.enqueue("conv-a", "a2")
        queue.enqueue("conv-a", "a3")
        queue.enqueue("conv-b", "b2")

        val syncer = RecordingSyncer()
        val report = queue.drain(syncer)

        // 5 updates, 2 conversations, exactly 2 sync calls.
        assertThat(syncer.batches).hasSize(2)
        val byConv = syncer.batches.associateBy { it.conversationId }
        assertThat(byConv.getValue("conv-a").payloads).containsExactly("a1", "a2", "a3").inOrder()
        assertThat(byConv.getValue("conv-b").payloads).containsExactly("b1", "b2").inOrder()

        assertThat(report.conversationsSynced).isEqualTo(2)
        assertThat(report.updatesSynced).isEqualTo(5)
        assertThat(queue.pendingCount()).isEqualTo(0)
    }

    @Test
    fun `drain on empty queue is a no-op`() = runBlocking<Unit> {
        val syncer = RecordingSyncer()
        val report = queue.drain(syncer)
        assertThat(syncer.batches).isEmpty()
        assertThat(report).isEqualTo(DrainReport())
    }

    @Test
    fun `transient failure keeps updates pending and counts the attempt`() = runBlocking<Unit> {
        queue.enqueue("conv-a", "a1")
        queue.enqueue("conv-a", "a2")

        val syncer = RecordingSyncer(failWith = { SocketTimeoutException("offline") })
        val report = queue.drain(syncer)

        assertThat(report.transientFailures).isEqualTo(1)
        assertThat(report.updatesSynced).isEqualTo(0)
        val pending = queue.pending()
        assertThat(pending).hasSize(2)
        assertThat(pending.map { it.attemptCount }).containsExactly(1, 1)
    }

    @Test
    fun `semantic failure dead-letters the batch instead of retrying forever`() = runBlocking<Unit> {
        queue.enqueue("conv-a", "malformed-tool-call")

        val syncer = RecordingSyncer(
            failWith = {
                SyncHttpException(
                    status = 400,
                    errorBody = """{"error": {"type": "invalid_request_error", "message": "bad tool"}}""",
                )
            },
        )
        val report = queue.drain(syncer)

        assertThat(report.semanticFailures).isEqualTo(1)
        assertThat(queue.pendingCount()).isEqualTo(0)
        assertThat(queue.deadLetters().map { it.payload }).containsExactly("malformed-tool-call")

        // A later drain must NOT retry dead letters.
        val secondSyncer = RecordingSyncer()
        queue.drain(secondSyncer)
        assertThat(secondSyncer.batches).isEmpty()
    }

    @Test
    fun `rate-limited batch stays pending - 429 is transient despite being 4xx`() = runBlocking<Unit> {
        queue.enqueue("conv-a", "a1")
        val syncer = RecordingSyncer(failWith = { SyncHttpException(status = 429) })
        val report = queue.drain(syncer)
        assertThat(report.transientFailures).isEqualTo(1)
        assertThat(queue.pendingCount()).isEqualTo(1)
    }

    @Test
    fun `partial failure - successful conversations are removed, failed ones stay`() = runBlocking<Unit> {
        queue.enqueue("conv-ok", "ok1")
        queue.enqueue("conv-broken", "broken1")
        queue.enqueue("conv-ok", "ok2")

        val syncer = RecordingSyncer(
            failWith = { if (it.conversationId == "conv-broken") SocketTimeoutException() else null },
        )
        val report = queue.drain(syncer)

        assertThat(report.conversationsSynced).isEqualTo(1)
        assertThat(report.updatesSynced).isEqualTo(2)
        assertThat(report.transientFailures).isEqualTo(1)
        assertThat(queue.pending().map { it.payload }).containsExactly("broken1")
    }

    @Test
    fun `transient failures exhaust maxAttempts into the dead letter queue`() = runBlocking<Unit> {
        queue.enqueue("conv-a", "a1")
        val syncer = RecordingSyncer(failWith = { SocketTimeoutException("still offline") })

        queue.drain(syncer) // attempt 1
        queue.drain(syncer) // attempt 2
        assertThat(queue.pendingCount()).isEqualTo(1)
        queue.drain(syncer) // attempt 3 == maxAttempts → dead
        assertThat(queue.pendingCount()).isEqualTo(0)
        assertThat(queue.deadLetters()).hasSize(1)
        assertThat(syncer.batches).hasSize(3)
    }

    @Test
    fun `failure before completion leaves the batch intact for redelivery`() = runBlocking<Unit> {
        // At-least-once: the syncer received the batch but died before finishing
        // (process kill, network drop mid-request). Nothing may be lost.
        queue.enqueue("conv-a", "a1")
        queue.enqueue("conv-a", "a2")

        val crashing = RecordingSyncer(failWith = { SocketTimeoutException("died mid-sync") })
        queue.drain(crashing)
        assertThat(crashing.batches.single().payloads).containsExactly("a1", "a2").inOrder()

        val retry = RecordingSyncer()
        val report = queue.drain(retry)
        assertThat(retry.batches.single().payloads).containsExactly("a1", "a2").inOrder()
        assertThat(report.updatesSynced).isEqualTo(2)
        assertThat(queue.pendingCount()).isEqualTo(0)
    }

    @Test
    fun `updates enqueued after a drain are picked up by the next drain`() = runBlocking<Unit> {
        queue.enqueue("conv-a", "a1")
        queue.drain(RecordingSyncer())

        queue.enqueue("conv-a", "a2")
        val syncer = RecordingSyncer()
        queue.drain(syncer)
        assertThat(syncer.batches.single().payloads).containsExactly("a2")
    }

    @Test
    fun `purge dead letters empties only the dead letter queue`() = runBlocking<Unit> {
        queue.enqueue("conv-dead", "d1")
        queue.enqueue("conv-live", "l1")
        queue.drain(
            RecordingSyncer(
                failWith = {
                    if (it.conversationId == "conv-dead") SyncHttpException(status = 400) else SocketTimeoutException()
                },
            ),
        )
        assertThat(queue.deadLetters()).hasSize(1)
        assertThat(queue.purgeDeadLetters()).isEqualTo(1)
        assertThat(queue.deadLetters()).isEmpty()
        assertThat(queue.pendingCount()).isEqualTo(1) // conv-live untouched
    }
}
