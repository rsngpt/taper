package dev.taper.benchmark

import com.squareup.moshi.JsonWriter
import java.io.File
import kotlin.random.Random
import okio.buffer
import okio.sink

/**
 * Generates synthetic agent-response JSON payloads of a target size, streamed
 * straight to disk so generation itself never holds the payload in memory.
 *
 * Shape mimics a chat-completions-style agent response: metadata, a long list
 * of messages with sizeable content strings and tool calls, and a usage block.
 */
object SyntheticPayloads {

    /** Writes a payload of at least [targetBytes] to [file]; returns actual size. */
    fun generate(file: File, targetBytes: Long, seed: Long = 42L): Long {
        val random = Random(seed)
        file.sink().buffer().use { sink ->
            JsonWriter.of(sink).use { writer ->
                writer.beginObject()
                writer.name("id").value("resp_${seed}_$targetBytes")
                writer.name("model").value("agent-model-large")
                writer.name("created").value(1_768_000_000L)
                writer.name("conversation_id").value("conv_bench")
                writer.name("messages")
                writer.beginArray()
                var messageIndex = 0
                // Leave room for the usage/metadata tail; each message is ~1.6KB.
                while (sink.buffer.size + fileBytesFlushed(file) < targetBytes - TAIL_RESERVE) {
                    writeMessage(writer, messageIndex, random)
                    messageIndex++
                    if (messageIndex % 64 == 0) sink.flush() // keep the okio buffer small
                }
                writer.endArray()
                writer.name("usage")
                writer.beginObject()
                writer.name("input_tokens").value(123_456L)
                writer.name("output_tokens").value(messageIndex * 200L)
                writer.name("total_tokens").value(123_456L + messageIndex * 200L)
                writer.endObject()
                writer.name("metadata")
                writer.beginObject()
                writer.name("region").value("synthetic")
                writer.name("truncated").value(false)
                writer.name("message_count").value(messageIndex.toLong())
                writer.endObject()
                writer.endObject()
            }
        }
        return file.length()
    }

    private fun fileBytesFlushed(file: File): Long = file.length()

    private fun writeMessage(writer: JsonWriter, index: Int, random: Random) {
        writer.beginObject()
        writer.name("index").value(index.toLong())
        writer.name("role").value(if (index % 2 == 0) "assistant" else "user")
        writer.name("content").value(loremChunk(random, CONTENT_CHARS))
        writer.name("tool_calls")
        writer.beginArray()
        if (index % 3 == 0) {
            writer.beginObject()
            writer.name("name").value("search_documents")
            writer.name("arguments")
            writer.beginObject()
            writer.name("query").value(loremChunk(random, 80))
            writer.name("top_k").value(8L)
            writer.endObject()
            writer.endObject()
        }
        writer.endArray()
        writer.name("stop_reason").value("end_turn")
        writer.endObject()
    }

    private fun loremChunk(random: Random, chars: Int): String {
        val sb = StringBuilder(chars + 16)
        while (sb.length < chars) {
            sb.append(WORDS[random.nextInt(WORDS.size)]).append(' ')
        }
        return sb.toString()
    }

    private const val CONTENT_CHARS = 1400
    private const val TAIL_RESERVE = 512L

    private val WORDS = listOf(
        "context", "agent", "memory", "token", "stream", "device", "budget",
        "latency", "retry", "conversation", "payload", "android", "heap",
        "buffer", "session", "model", "inference", "battery", "network",
    )
}
