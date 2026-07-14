package dev.taper.parser

import com.squareup.moshi.JsonWriter
import java.io.File
import okio.buffer
import okio.sink

/**
 * Streams a synthetic agent-response payload of at least [targetBytes] to [file].
 *
 * Deliberately object-dense (short strings, nested tool-call objects) rather
 * than one giant string: DOM parsers pay per NODE (map entries, boxed numbers,
 * string headers), so this is the shape where tree-mode parsing hurts — and it
 * is exactly the shape of tool-call-heavy agent responses. Note that the JVM's
 * compact strings make ASCII string CONTENT cheap (1 byte/char), so a
 * string-dominated payload would understate DOM cost.
 */
internal fun writeSyntheticPayload(file: File, targetBytes: Long): Long {
    file.sink().buffer().use { sink ->
        JsonWriter.of(sink).use { writer ->
            writer.beginObject()
            writer.name("model").value("agent-model-large")
            writer.name("messages")
            writer.beginArray()
            var i = 0
            while (file.length() + sink.buffer.size < targetBytes - 256) {
                writer.beginObject()
                writer.name("index").value(i.toLong())
                writer.name("role").value(if (i % 2 == 0) "assistant" else "user")
                writer.name("content").value("step $i of the running conversation history")
                writer.name("tool_calls")
                writer.beginArray()
                writer.beginObject()
                writer.name("id").value("call_$i")
                writer.name("name").value("search_documents")
                writer.name("arguments")
                writer.beginObject()
                writer.name("query").value("agent context chunk $i")
                writer.name("top_k").value(8L)
                writer.name("offset").value(i.toLong())
                writer.endObject()
                writer.endObject()
                writer.endArray()
                writer.name("stop_reason").value("end_turn")
                writer.endObject()
                i++
                if (i % 64 == 0) sink.flush()
            }
            writer.endArray()
            writer.name("usage")
            writer.beginObject()
            writer.name("total_tokens").value(987_654L)
            writer.endObject()
            writer.endObject()
        }
    }
    return file.length()
}
