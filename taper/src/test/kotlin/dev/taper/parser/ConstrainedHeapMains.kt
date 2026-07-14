package dev.taper.parser

import com.google.gson.JsonParser
import java.io.File
import kotlin.system.exitProcess

/**
 * Entry points executed in a CHILD JVM with a deliberately tiny heap (-Xmx) by
 * [ConstrainedHeapTest]. Exit codes:
 *   0  = parsed successfully
 *   42 = OutOfMemoryError (the expected DOM outcome)
 *   1  = any other failure
 */
internal const val EXIT_OK = 0
internal const val EXIT_OOM = 42

/** DOM baseline: Gson tree mode over the whole document. */
internal object DomParseMain {
    @JvmStatic
    fun main(args: Array<String>) {
        runCatchingOom {
            val root = File(args[0]).reader().use { JsonParser.parseReader(it) }.asJsonObject
            check(root.getAsJsonArray("messages").size() > 0)
        }
    }
}

/** Taper streaming extraction over the same document. */
internal object StreamingParseMain {
    @JvmStatic
    fun main(args: Array<String>) {
        runCatchingOom {
            val result = File(args[0]).inputStream().use {
                TaperParser().parse(it, setOf("model", "usage.total_tokens"))
            }
            check(result.firstString("model") == "agent-model-large")
            check(result.firstLong("usage.total_tokens") == 987_654L)
        }
    }
}

private inline fun runCatchingOom(block: () -> Unit) {
    try {
        block()
    } catch (e: Throwable) {
        // Gson wraps OutOfMemoryError in JsonParseException — walk the cause chain.
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is OutOfMemoryError) exitProcess(EXIT_OOM)
            cause = cause.cause
        }
        e.printStackTrace()
        exitProcess(1)
    }
    exitProcess(EXIT_OK)
}
