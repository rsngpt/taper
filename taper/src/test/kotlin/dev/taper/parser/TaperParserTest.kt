package dev.taper.parser

import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.JsonEncodingException
import java.io.ByteArrayInputStream
import java.io.EOFException
import org.junit.Assert.assertThrows
import org.junit.Test

class TaperParserTest {

    private val parser = TaperParser()

    private fun parse(json: String, fields: Set<String>): TaperResult =
        parser.parse(ByteArrayInputStream(json.toByteArray()), fields)

    private val agentResponse = """
        {
          "id": "resp_1",
          "model": "agent-model-large",
          "messages": [
            {"role": "user", "content": "hello", "tool_calls": []},
            {"role": "assistant", "content": "hi there",
             "tool_calls": [{"name": "search", "arguments": {"query": "q1", "top_k": 8}}]},
            {"role": "assistant", "content": "done",
             "tool_calls": [{"name": "fetch", "arguments": {"url": "https://example.test"}}]}
          ],
          "usage": {"input_tokens": 10, "output_tokens": 32, "total_tokens": 42},
          "metadata": {"region": "eu", "truncated": false, "score": 0.75}
        }
    """.trimIndent()

    @Test
    fun `extracts scalar fields`() {
        val result = parse(agentResponse, setOf("model", "usage.total_tokens"))
        assertThat(result.firstString("model")).isEqualTo("agent-model-large")
        assertThat(result.firstLong("usage.total_tokens")).isEqualTo(42L)
    }

    @Test
    fun `extracts every array occurrence in document order`() {
        val result = parse(agentResponse, setOf("messages[].content"))
        assertThat(result.all("messages[].content"))
            .containsExactly("hello", "hi there", "done").inOrder()
    }

    @Test
    fun `document paths point at concrete locations`() {
        val result = parse(agentResponse, setOf("messages[].tool_calls[].name"))
        assertThat(result.matches("messages[].tool_calls[].name").map { it.documentPath })
            .containsExactly("messages[1].tool_calls[0].name", "messages[2].tool_calls[0].name")
            .inOrder()
    }

    @Test
    fun `star wildcard extracts all members at a level`() {
        val result = parse(agentResponse, setOf("metadata.*"))
        assertThat(result.all("metadata.*")).containsExactly("eu", false, 0.75).inOrder()
    }

    @Test
    fun `integers stay Long and fractions become Double`() {
        val result = parse(agentResponse, setOf("usage.input_tokens", "metadata.score"))
        assertThat(result.first("usage.input_tokens")).isEqualTo(10L)
        assertThat(result.first("metadata.score")).isEqualTo(0.75)
    }

    @Test
    fun `matched object subtree is materialised as a map`() {
        val result = parse(agentResponse, setOf("usage"))
        val usage = result.first("usage")
        assertThat(usage).isInstanceOf(Map::class.java)
        @Suppress("UNCHECKED_CAST")
        assertThat((usage as Map<String, Any?>)["total_tokens"]).isEqualTo(42.0)
    }

    @Test
    fun `null values are extracted as null but still counted as matches`() {
        val result = parse("""{"a": null, "b": 1}""", setOf("a"))
        assertThat(result.matchedFields).containsExactly("a")
        assertThat(result.first("a")).isNull()
    }

    @Test
    fun `overlapping expressions each receive the value`() {
        val result = parse(agentResponse, setOf("metadata.*", "metadata.region"))
        assertThat(result.all("metadata.region")).containsExactly("eu")
        assertThat(result.all("metadata.*")).contains("eu")
    }

    @Test
    fun `missing fields simply do not match`() {
        val result = parse(agentResponse, setOf("does.not.exist", "model"))
        assertThat(result.matchedFields).containsExactly("model")
        assertThat(result.all("does.not.exist")).isEmpty()
        assertThat(result.first("does.not.exist")).isNull()
    }

    @Test
    fun `top-level array documents are supported`() {
        val result = parse("""[{"id": "a"}, {"id": "b"}]""", setOf("[].id"))
        assertThat(result.all("[].id")).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `empty fields set extracts nothing but validates the document`() {
        assertThat(parse(agentResponse, emptySet()).isEmpty()).isTrue()
        assertThrows(EOFException::class.java) {
            parse("""{"unterminated": """, emptySet())
        }
    }

    @Test
    fun `malformed json throws instead of returning partial data`() {
        assertThrows(JsonEncodingException::class.java) {
            parse("""{"a": nope}""", setOf("a"))
        }
    }

    @Test
    fun `scalar where a deeper path expects structure is skipped, not crashed`() {
        val result = parse("""{"messages": "unexpectedly-a-string"}""", setOf("messages[].content"))
        assertThat(result.isEmpty()).isTrue()
    }

    @Test
    fun `streaming sink receives values incrementally without collecting`() {
        val seen = mutableListOf<Pair<String, Any?>>()
        parser.parse(
            ByteArrayInputStream(agentResponse.toByteArray()),
            setOf("messages[].role"),
        ) { field, documentPath, value ->
            seen += documentPath to value
            assertThat(field.expression).isEqualTo("messages[].role")
        }
        assertThat(seen).containsExactly(
            "messages[0].role" to "user",
            "messages[1].role" to "assistant",
            "messages[2].role" to "assistant",
        ).inOrder()
    }

    @Test
    fun `deeply nested documents do not overflow with reasonable depth`() {
        val depth = 200
        val json = buildString {
            repeat(depth) { append("""{"n":""") }
            append("1")
            repeat(depth) { append("}") }
        }
        val path = (1..depth).joinToString(".") { "n" }
        val result = parse(json, setOf(path))
        assertThat(result.first(path)).isEqualTo(1L)
    }

    @Test
    fun `multi-megabyte payload parses and extracts correctly`() {
        val file = kotlin.io.path.createTempFile("taper-big", ".json").toFile()
        try {
            val size = writeSyntheticPayload(file, 8L * 1024 * 1024)
            assertThat(size).isAtLeast(8L * 1024 * 1024 - 4096)
            val result = file.inputStream().use {
                parser.parse(it, setOf("model", "usage.total_tokens", "messages[].role"))
            }
            assertThat(result.firstString("model")).isEqualTo("agent-model-large")
            assertThat(result.firstLong("usage.total_tokens")).isEqualTo(987_654L)
            assertThat(result.all("messages[].role").size).isGreaterThan(1000)
        } finally {
            file.delete()
        }
    }
}
