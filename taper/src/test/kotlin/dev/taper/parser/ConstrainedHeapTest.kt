package dev.taper.parser

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test

/**
 * Proves the core memory claim with real processes, not estimates:
 * the same payload that CANNOT be DOM-parsed inside a small heap streams
 * through Taper without trouble.
 *
 * Each strategy runs in a child JVM capped at [HEAP_CAP] so an OOM cannot take
 * down the test runner, and so the cap is exact rather than inferred.
 */
class ConstrainedHeapTest {

    @Test
    fun `dom parse of a 16MB payload dies with OOM inside a 32MB heap`() {
        val exit = runInChildJvm(DomParseMain::class.java.name, payload.absolutePath)
        assertThat(exit).isEqualTo(EXIT_OOM)
    }

    @Test
    fun `taper streams the same 16MB payload inside the same 32MB heap`() {
        val exit = runInChildJvm(StreamingParseMain::class.java.name, payload.absolutePath)
        assertThat(exit).isEqualTo(EXIT_OK)
    }

    private fun runInChildJvm(mainClass: String, vararg args: String): Int {
        val javaBin = File(System.getProperty("java.home"), "bin/java").absolutePath
        val process = ProcessBuilder(
            javaBin,
            "-Xmx$HEAP_CAP",
            "-XX:+UseSerialGC", // fail fast instead of thrashing near the cap
            "-cp",
            System.getProperty("java.class.path"),
            mainClass,
            *args,
        ).redirectErrorStream(true).start()
        val finished = process.waitFor(120, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            error("Child JVM for $mainClass timed out")
        }
        val output = process.inputStream.bufferedReader().readText()
        // Surface child output on unexpected exits to keep failures debuggable.
        if (process.exitValue() !in setOf(EXIT_OK, EXIT_OOM)) {
            System.err.println("Child $mainClass output:\n$output")
        }
        return process.exitValue()
    }

    companion object {
        private const val HEAP_CAP = "32m"
        private const val PAYLOAD_BYTES = 16L * 1024 * 1024

        private lateinit var payload: File

        @JvmStatic
        @BeforeClass
        fun generatePayload() {
            payload = File.createTempFile("taper-constrained", ".json")
            writeSyntheticPayload(payload, PAYLOAD_BYTES)
        }

        @JvmStatic
        @AfterClass
        fun deletePayload() {
            payload.delete()
        }
    }
}
