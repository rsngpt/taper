package dev.taper.demo

import com.squareup.moshi.JsonWriter
import dev.taper.classify.AgentFailure
import dev.taper.classify.ExceptionClassifier
import dev.taper.classify.FailureCategory
import dev.taper.parser.TaperParser
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import okio.Buffer

/**
 * A minimal agent loop against a live Ollama server, with Taper wired at every seam:
 *
 *  - every HTTP response body is parsed by [TaperParser] straight off the network
 *    stream with declared fields of interest — the full payload is never
 *    materialised as a DOM or even as a String;
 *  - every failure is classified by [ExceptionClassifier] before anyone decides
 *    to retry, queue, or halt.
 *
 * Uses bare [HttpURLConnection] on purpose: Taper is HTTP-client-agnostic and the
 * demo should not smuggle in an extra networking stack.
 */
class OllamaAgent(
    private val baseUrl: String,
    private val model: String,
    private val classifier: ExceptionClassifier = ExceptionClassifier(),
) {
    private val parser = TaperParser()

    /** Rolling conversation history, replayed on every request (Ollama chat API is stateless). */
    private val history = mutableListOf<Message>()

    data class Message(
        val role: String,
        val content: String,
        /** Present when the assistant asked for a tool; echoed back before the tool result. */
        val toolName: String? = null,
        val toolArgumentsJson: String? = null,
    )

    sealed interface TurnResult {
        /** Assistant answered; [toolTrace] lists any tool round-trips that happened. */
        data class Reply(val content: String, val toolTrace: List<String>, val tokens: Long?) : TurnResult

        /** The request failed; [category] is what the classifier decided. */
        data class Failure(
            val category: FailureCategory,
            val detail: String,
        ) : TurnResult
    }

    /**
     * Sends one user turn, transparently resolving at most [maxToolRounds] tool
     * calls. Network/HTTP failures never throw — they come back classified.
     */
    fun send(userText: String, maxToolRounds: Int = 3): TurnResult {
        history += Message("user", userText)
        val toolTrace = mutableListOf<String>()
        var rounds = 0
        while (true) {
            val outcome = try {
                postChat()
            } catch (e: Exception) {
                // A failed turn never happened as far as the model is concerned;
                // the caller owns retrying it (e.g. via the sync queue).
                history.removeAt(history.lastIndex)
                val category = classifier.classify(AgentFailure(exception = e))
                return TurnResult.Failure(category, e.javaClass.simpleName + ": " + e.message)
            }
            when (outcome) {
                is HttpOutcome.Error -> {
                    history.removeAt(history.lastIndex)
                    val category = classifier.classify(
                        AgentFailure(httpStatus = outcome.status, responseBody = outcome.body),
                    )
                    return TurnResult.Failure(category, "HTTP ${outcome.status}: ${outcome.body.take(200)}")
                }
                is HttpOutcome.Success -> {
                    val toolName = outcome.toolName
                    if (toolName == null || rounds >= maxToolRounds) {
                        history += Message("assistant", outcome.content)
                        return TurnResult.Reply(outcome.content, toolTrace, outcome.totalTokens)
                    }
                    // Tool round: run the tool locally, append both sides, loop.
                    val result = runTool(toolName, outcome.toolArgumentsJson)
                    toolTrace += "$toolName(${outcome.toolArgumentsJson}) → $result"
                    history += Message(
                        "assistant", outcome.content,
                        toolName = toolName, toolArgumentsJson = outcome.toolArgumentsJson,
                    )
                    history += Message("tool", result)
                    rounds++
                }
            }
        }
    }

    /** Replays a queued payload (used by the sync queue's drainer). Throws on failure. */
    fun replay(userText: String): String {
        history += Message("user", userText)
        try {
            return when (val outcome = postChat()) {
                is HttpOutcome.Success -> {
                    history += Message("assistant", outcome.content)
                    outcome.content
                }
                is HttpOutcome.Error ->
                    throw dev.taper.queue.SyncHttpException(outcome.status, outcome.body)
            }
        } catch (e: Exception) {
            history.removeAt(history.lastIndex) // failed replay stays out of history; the queue still holds it
            throw e
        }
    }

    /**
     * CHAOS BUTTON: posts deliberately malformed JSON so the server answers with a
     * real 4xx + error body — the SEMANTIC drill uses genuine server behaviour,
     * not a mocked response.
     */
    fun sendMalformedRequest(): TurnResult.Failure {
        val outcome = try {
            post(body = """{"model": "$model", "messages": "this-should-be-an-array"}""")
        } catch (e: Exception) {
            val category = classifier.classify(AgentFailure(exception = e))
            return TurnResult.Failure(category, e.javaClass.simpleName + ": " + e.message)
        }
        return when (outcome) {
            is HttpOutcome.Error -> {
                val category = classifier.classify(
                    AgentFailure(httpStatus = outcome.status, responseBody = outcome.body),
                )
                TurnResult.Failure(category, "HTTP ${outcome.status}: ${outcome.body.take(200)}")
            }
            is HttpOutcome.Success ->
                TurnResult.Failure(FailureCategory.SEMANTIC, "server unexpectedly accepted malformed JSON")
        }
    }

    // ---- HTTP + parsing ----

    private sealed interface HttpOutcome {
        data class Success(
            val content: String,
            val toolName: String?,
            val toolArgumentsJson: String?,
            val totalTokens: Long?,
        ) : HttpOutcome

        data class Error(val status: Int, val body: String) : HttpOutcome
    }

    private fun postChat(): HttpOutcome = post(buildChatRequest())

    private fun post(body: String): HttpOutcome {
        val connection = URL("$baseUrl/api/chat").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = 10_000
            // Generous read timeout: stream=false means the model generates the whole
            // reply before responding, and a cold model load takes a while.
            connection.readTimeout = 180_000
            connection.outputStream.use { it.write(body.toByteArray()) }

            val status = connection.responseCode
            if (status >= 400) {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                return HttpOutcome.Error(status, errorBody)
            }

            // The showcase: extract only what the app needs, straight off the stream.
            val result = connection.inputStream.use {
                parser.parse(it, FIELDS_OF_INTEREST)
            }
            val arguments = result.first("message.tool_calls[].function.arguments")
            return HttpOutcome.Success(
                content = result.firstString("message.content").orEmpty(),
                toolName = result.firstString("message.tool_calls[].function.name"),
                toolArgumentsJson = arguments?.let(::toJson),
                totalTokens = (result.firstLong("prompt_eval_count") ?: 0) +
                    (result.firstLong("eval_count") ?: 0),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun buildChatRequest(): String {
        val buffer = Buffer()
        JsonWriter.of(buffer).use { w ->
            w.beginObject()
            w.name("model").value(model)
            w.name("stream").value(false)
            w.name("messages")
            w.beginArray()
            for (m in history) {
                w.beginObject()
                w.name("role").value(m.role)
                w.name("content").value(m.content)
                if (m.toolName != null) {
                    w.name("tool_calls")
                    w.beginArray()
                    w.beginObject()
                    w.name("function")
                    w.beginObject()
                    w.name("name").value(m.toolName)
                    w.name("arguments")
                    // Raw pass-through of the arguments object we extracted earlier.
                    // NB: jsonValue(String) would encode a QUOTED string — the live 400
                    // from Ollama caught exactly that mistake; valueSink() writes raw JSON.
                    w.valueSink().use { it.writeUtf8(m.toolArgumentsJson ?: "{}") }
                    w.endObject()
                    w.endObject()
                    w.endArray()
                }
                w.endObject()
            }
            w.endArray()
            w.name("tools")
            w.valueSink().use { it.writeUtf8(TOOLS_JSON) }
            w.endObject()
        }
        return buffer.readUtf8()
    }

    private fun runTool(name: String, argumentsJson: String?): String = when (name) {
        "get_device_time" -> SimpleDateFormat("EEE dd MMM yyyy, HH:mm:ss z", Locale.US).format(Date())
        else -> "error: unknown tool '$name'"
    }

    /** Serialises a value extracted by TaperParser (Map/List/scalar) back to JSON. */
    private fun toJson(value: Any?): String {
        val buffer = Buffer()
        JsonWriter.of(buffer).use { writeValue(it, value) }
        return buffer.readUtf8()
    }

    private fun writeValue(w: JsonWriter, value: Any?) {
        when (value) {
            null -> w.nullValue()
            is String -> w.value(value)
            is Boolean -> w.value(value)
            is Number -> w.value(value)
            is Map<*, *> -> {
                w.beginObject()
                for ((k, v) in value) {
                    w.name(k.toString())
                    writeValue(w, v)
                }
                w.endObject()
            }
            is List<*> -> {
                w.beginArray()
                value.forEach { writeValue(w, it) }
                w.endArray()
            }
            else -> w.value(value.toString())
        }
    }

    companion object {
        private val FIELDS_OF_INTEREST = setOf(
            "message.content",
            "message.tool_calls[].function.name",
            "message.tool_calls[].function.arguments",
            "prompt_eval_count",
            "eval_count",
        )

        private val TOOLS_JSON = """
            [{"type": "function", "function": {
                "name": "get_device_time",
                "description": "Returns the device's current date and time.",
                "parameters": {"type": "object", "properties": {}, "required": []}
            }}]
        """.trimIndent()
    }
}
