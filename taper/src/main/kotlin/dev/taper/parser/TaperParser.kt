package dev.taper.parser

import com.squareup.moshi.JsonReader
import java.io.InputStream
import okio.buffer
import okio.source

/**
 * Streaming JSON ingestion for large agent-response payloads.
 *
 * Instead of materialising the whole document as an in-memory DOM tree
 * (`org.json.JSONObject`, Gson tree mode), Taper walks the token stream and
 * extracts only the caller's declared *fields of interest*. Everything else is
 * skipped token-by-token via [JsonReader.skipValue] and never allocated.
 *
 * Memory behaviour, stated precisely:
 *  - Unmatched content costs O(nesting depth), not O(document size).
 *  - A matched *scalar* field costs only that scalar.
 *  - A matched *object/array* field is materialised (only that subtree) via
 *    [JsonReader.readJsonValue]. Selecting the document root therefore degrades
 *    to DOM parsing by design — select the narrowest paths you actually need.
 *
 * Usage:
 * ```
 * val result = TaperParser().parse(
 *     inputStream,
 *     fieldsOfInterest = setOf("model", "messages[].content", "usage.total_tokens"),
 * )
 * val model = result.firstString("model")
 * ```
 */
class TaperParser(
    private val engine: ParserEngine = MoshiParserEngine(),
) {

    /**
     * Parses [input] and collects every occurrence of [fieldsOfInterest] into a [TaperResult].
     *
     * An empty [fieldsOfInterest] set extracts nothing (the stream is still validated
     * as well-formed JSON); it does NOT mean "extract everything".
     *
     * @throws com.squareup.moshi.JsonEncodingException on malformed JSON.
     * @throws java.io.IOException on stream failures.
     */
    fun parse(input: InputStream, fieldsOfInterest: Set<String>): TaperResult {
        val collector = CollectingSink()
        parse(input, fieldsOfInterest, collector)
        return collector.toResult()
    }

    /**
     * Streaming variant: extracted values are pushed to [sink] as they are read,
     * so the caller is never forced to hold all matches in memory at once
     * (useful when a field of interest repeats many times, e.g. `messages[].content`).
     */
    fun parse(input: InputStream, fieldsOfInterest: Set<String>, sink: ExtractionSink) {
        val compiled = fieldsOfInterest.map(FieldPath::parse)
        input.source().buffer().use { source ->
            engine.extract(source, compiled, sink)
        }
    }
}

/** Receives extracted values in document order. */
fun interface ExtractionSink {
    /**
     * @param field the [FieldPath] expression that matched.
     * @param documentPath the concrete location, e.g. `messages[3].content`.
     * @param value String / Long / Double / Boolean / null for scalars;
     *              Map<String, Any?> / List<Any?> for matched subtrees.
     */
    fun onValue(field: FieldPath, documentPath: String, value: Any?)
}

/**
 * v2 EXTENSION POINT — deliberately not implemented in v1.
 *
 * Abstracts how bytes become extracted values so that alternative engines can be
 * plugged in later without breaking [TaperParser]'s public API. Planned candidates:
 *  - a JNI/native engine writing into ashmem-backed buffers,
 *  - a FlatBuffers-emitting engine for zero-copy hand-off between processes.
 * v1 ships exactly one implementation, [MoshiParserEngine].
 */
interface ParserEngine {
    fun extract(source: okio.BufferedSource, fields: List<FieldPath>, sink: ExtractionSink)
}

/** Default engine built on Moshi's streaming [JsonReader]. Allocates no DOM for unmatched content. */
class MoshiParserEngine : ParserEngine {

    override fun extract(source: okio.BufferedSource, fields: List<FieldPath>, sink: ExtractionSink) {
        val reader = JsonReader.of(source)
        val stack = mutableListOf<PathStep>()
        if (fields.isEmpty()) {
            reader.skipValue() // still consumes + validates the document
            return
        }
        dispatch(reader, stack, fields, sink)
    }

    /** Decides, for the value at the current position, whether to extract, descend, or skip. */
    private fun dispatch(
        reader: JsonReader,
        stack: MutableList<PathStep>,
        fields: List<FieldPath>,
        sink: ExtractionSink,
    ) {
        // Read the value once, then fan it out to every field expression matching this
        // location. Written without intermediate collections: dispatch runs per token.
        var matched = false
        var value: Any? = null
        var documentPath = ""
        for (field in fields) {
            if (field.matches(stack)) {
                if (!matched) {
                    value = readValue(reader)
                    documentPath = stack.render()
                    matched = true
                }
                sink.onValue(field, documentPath, value)
            }
        }
        if (matched) return
        val shouldDescend = fields.any { it.isPrefixOf(stack) }
        if (!shouldDescend) {
            reader.skipValue()
            return
        }
        when (reader.peek()) {
            JsonReader.Token.BEGIN_OBJECT -> {
                reader.beginObject()
                while (reader.hasNext()) {
                    stack.add(PathStep.Key(reader.nextName()))
                    dispatch(reader, stack, fields, sink)
                    stack.removeAt(stack.lastIndex)
                }
                reader.endObject()
            }
            JsonReader.Token.BEGIN_ARRAY -> {
                reader.beginArray()
                var index = 0
                while (reader.hasNext()) {
                    stack.add(PathStep.Index(index))
                    dispatch(reader, stack, fields, sink)
                    stack.removeAt(stack.lastIndex)
                    index++
                }
                reader.endArray()
            }
            // A prefix exists but the document has a scalar here; nothing to descend into.
            else -> reader.skipValue()
        }
    }

    private fun readValue(reader: JsonReader): Any? = when (reader.peek()) {
        JsonReader.Token.STRING -> reader.nextString()
        JsonReader.Token.BOOLEAN -> reader.nextBoolean()
        JsonReader.Token.NULL -> reader.nextNull<Any>()
        JsonReader.Token.NUMBER -> readNumber(reader)
        // Matched subtree: materialise just this branch. Documented cost — see class KDoc.
        else -> reader.readJsonValue()
    }

    /** Preserves integers as Long; falls back to Double for fractions/exponents. */
    private fun readNumber(reader: JsonReader): Any {
        val literal = reader.nextString()
        return literal.toLongOrNull() ?: literal.toDouble()
    }
}

/** Result of a collecting parse: all matched values, keyed by field expression. */
class TaperResult internal constructor(
    private val values: Map<String, List<Match>>,
) {
    /** One extracted occurrence of a field of interest. */
    data class Match(val documentPath: String, val value: Any?)

    /** Field expressions that matched at least once. */
    val matchedFields: Set<String> get() = values.keys

    /** All occurrences of [field], in document order. Empty if it never matched. */
    fun matches(field: String): List<Match> = values[field].orEmpty()

    /** All extracted values of [field], in document order. */
    fun all(field: String): List<Any?> = matches(field).map { it.value }

    /** First occurrence of [field], or null. */
    fun first(field: String): Any? = matches(field).firstOrNull()?.value

    fun firstString(field: String): String? = first(field) as? String
    fun firstLong(field: String): Long? = first(field) as? Long
    fun firstBoolean(field: String): Boolean? = first(field) as? Boolean

    fun isEmpty(): Boolean = values.isEmpty()

    override fun toString(): String =
        "TaperResult(${values.entries.joinToString { "${it.key}=${it.value.size} match(es)" }})"
}

/** Default sink: accumulates matches on the heap and produces a [TaperResult]. */
class CollectingSink : ExtractionSink {
    private val values = LinkedHashMap<String, MutableList<TaperResult.Match>>()

    override fun onValue(field: FieldPath, documentPath: String, value: Any?) {
        values.getOrPut(field.expression) { mutableListOf() }
            .add(TaperResult.Match(documentPath, value))
    }

    fun toResult(): TaperResult = TaperResult(values)
}
