package dev.taper.benchmark

import com.google.gson.JsonParser
import dev.taper.parser.TaperParser
import java.io.File
import org.json.JSONObject

/**
 * The parsing strategies under measurement. Each returns a small "proof of work"
 * value derived from the parsed data (and the strategies compute the SAME facts),
 * so the JIT cannot dead-code-eliminate the parse and the comparison is honest:
 * every strategy must surface model name, message count, and total tokens.
 */
sealed interface ParseStrategy {
    val id: String
    fun parse(file: File): ProofOfWork

    data class ProofOfWork(val model: String?, val messageCount: Int, val totalTokens: Long)

    /**
     * The naive baseline most apps ship: read the whole body into a String,
     * hand it to org.json. Costs the String AND the full DOM tree.
     */
    data object OrgJsonDom : ParseStrategy {
        override val id = "dom-orgjson"
        override fun parse(file: File): ProofOfWork {
            val root = JSONObject(file.readText())
            val messages = root.getJSONArray("messages")
            return ProofOfWork(
                model = root.optString("model"),
                messageCount = messages.length(),
                totalTokens = root.getJSONObject("usage").getLong("total_tokens"),
            )
        }
    }

    /**
     * Gson tree mode reading from a Reader — no full-payload String, but still
     * materialises the entire DOM tree.
     */
    data object GsonTreeDom : ParseStrategy {
        override val id = "dom-gson-tree"
        override fun parse(file: File): ProofOfWork {
            val root = file.reader().use { JsonParser.parseReader(it) }.asJsonObject
            return ProofOfWork(
                model = root.get("model")?.asString,
                messageCount = root.getAsJsonArray("messages").size(),
                totalTokens = root.getAsJsonObject("usage").get("total_tokens").asLong,
            )
        }
    }

    /** Taper streaming extraction of only the fields the caller needs. */
    data object TaperStreaming : ParseStrategy {
        override val id = "taper-streaming"
        private val fields = setOf("model", "messages[].role", "usage.total_tokens")
        override fun parse(file: File): ProofOfWork {
            val result = file.inputStream().use { TaperParser().parse(it, fields) }
            return ProofOfWork(
                model = result.firstString("model"),
                messageCount = result.all("messages[].role").size,
                totalTokens = result.firstLong("usage.total_tokens") ?: -1L,
            )
        }
    }

    companion object {
        val ALL = listOf(OrgJsonDom, GsonTreeDom, TaperStreaming)
    }
}
