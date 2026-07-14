package dev.taper.benchmark

import android.os.Debug
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Measures peak memory of DOM parsing vs Taper streaming on synthetic
 * agent-response payloads, on a real device/emulator under the real per-app
 * heap limit (no largeHeap).
 *
 * Methodology:
 *  - One (strategy, size) pair per test method; the Test Orchestrator runs each
 *    in a fresh process, so measurements cannot contaminate each other and an
 *    OOM only kills that test's process (counted as a crash for that row).
 *  - [ITERATIONS] runs per pair; a sampler thread records peak Java heap
 *    (Runtime) and peak PSS ([Debug.getPss]) during the parse.
 *  - Every strategy must produce the same proof-of-work facts, preventing
 *    dead-code elimination and keeping the comparison honest.
 *
 * Results are appended as CSV to the app's external files dir and logged with
 * tag [TAG]; the repo's `run_benchmark.sh` pulls and formats them.
 */
@RunWith(Parameterized::class)
class MemoryBenchmark(
    private val strategy: ParseStrategy,
    private val payloadBytes: Long,
    private val label: String,
) {

    @Test
    fun measure() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val payloadFile = File(context.cacheDir, "payload-$label.json")
        val actualSize = SyntheticPayloads.generate(payloadFile, payloadBytes)

        val resultsFile = File(context.getExternalFilesDir(null), RESULTS_FILE)
        try {
            repeat(ITERATIONS) { iteration ->
                val row = measureOnce(actualSize, iteration)
                resultsFile.appendText(row + "\n")
                Log.i(TAG, row)
            }
        } catch (e: Throwable) {
            // Parsers may wrap OutOfMemoryError (Gson: JsonParseException) — walk causes.
            var cause: Throwable? = e
            while (cause != null && cause !is OutOfMemoryError) cause = cause.cause
            if (cause !is OutOfMemoryError) throw e
            // Recoverable OOM (caught before the process died): record it.
            val row = listOf(
                strategy.id, label, actualSize, ITER_OOM, "OOM", "OOM", "OOM", 1,
            ).joinToString(",")
            resultsFile.appendText(row + "\n")
            Log.i(TAG, row)
            fail("OutOfMemoryError: ${strategy.id} on $label")
        } finally {
            payloadFile.delete()
        }
    }

    private fun measureOnce(actualSize: Long, iteration: Int): String {
        settleHeap()
        val baselineHeap = usedHeap()
        val baselinePss = Debug.getPss()

        val tracker = PeakTracker().also { it.start() }
        val startNanos = System.nanoTime()
        val proof = strategy.parse(File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "payload-$label.json",
        ))
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
        tracker.stop()

        check(proof.messageCount > 0 && proof.totalTokens > 0 && proof.model != null) {
            "proof of work failed for ${strategy.id}: $proof"
        }

        val peakHeapKb = ((tracker.peakHeap - baselineHeap).coerceAtLeast(0)) / 1024
        val peakPssKb = (tracker.peakPss - baselinePss).coerceAtLeast(0)
        return listOf(
            strategy.id, label, actualSize, iteration, peakHeapKb, peakPssKb, elapsedMs, 0,
        ).joinToString(",")
    }

    private fun settleHeap() {
        repeat(3) {
            Runtime.getRuntime().gc()
            System.runFinalization()
            Thread.sleep(150)
        }
    }

    private fun usedHeap(): Long =
        Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

    /** Samples Java heap every ~2ms and PSS every ~16ms during the parse. */
    private inner class PeakTracker {
        @Volatile private var running = true
        @Volatile var peakHeap = 0L
        @Volatile var peakPss = 0L
        private var thread: Thread? = null

        fun start() {
            thread = Thread {
                var tick = 0
                while (running) {
                    val heap = usedHeap()
                    if (heap > peakHeap) peakHeap = heap
                    if (tick % 8 == 0) {
                        val pss = Debug.getPss()
                        if (pss > peakPss) peakPss = pss
                    }
                    tick++
                    try {
                        Thread.sleep(2)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                }
            }.apply {
                isDaemon = true
                priority = Thread.MAX_PRIORITY // must keep sampling while the parser floods the heap
                start()
            }
        }

        fun stop() {
            running = false
            thread?.join(500)
            // Final samples so short parses are never missed entirely.
            val heap = usedHeap()
            if (heap > peakHeap) peakHeap = heap
            val pss = Debug.getPss()
            if (pss > peakPss) peakPss = pss
        }
    }

    companion object {
        private const val TAG = "TaperBenchmark"
        private const val RESULTS_FILE = "benchmark-results.csv"
        private const val ITERATIONS = 3
        private const val ITER_OOM = -1

        private const val KB = 1024L
        private const val MB = 1024L * KB

        /**
         * 100KB–10MB per the project spec, plus two clearly-labelled stress sizes
         * beyond spec to locate the on-device OOM cliff for DOM parsing.
         */
        @JvmStatic
        @Parameterized.Parameters(name = "{2}-{0}")
        fun parameters(): List<Array<Any>> {
            val sizes = linkedMapOf(
                "100KB" to 100 * KB,
                "500KB" to 500 * KB,
                "1MB" to 1 * MB,
                "2MB" to 2 * MB,
                "5MB" to 5 * MB,
                "10MB" to 10 * MB,
                "25MB-stress" to 25 * MB,
                "50MB-stress" to 50 * MB,
            )
            return sizes.flatMap { (label, bytes) ->
                ParseStrategy.ALL.map { strategy -> arrayOf<Any>(strategy, bytes, label) }
            }
        }
    }
}
