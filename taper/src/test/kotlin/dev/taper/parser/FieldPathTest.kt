package dev.taper.parser

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class FieldPathTest {

    @Test
    fun `simple key path matches exact location`() {
        val path = FieldPath.parse("usage.total_tokens")
        assertThat(path.matches(listOf(PathStep.Key("usage"), PathStep.Key("total_tokens")))).isTrue()
        assertThat(path.matches(listOf(PathStep.Key("usage")))).isFalse()
        assertThat(path.matches(listOf(PathStep.Key("usage"), PathStep.Key("input_tokens")))).isFalse()
    }

    @Test
    fun `array wildcard matches any index`() {
        val path = FieldPath.parse("messages[].content")
        assertThat(
            path.matches(listOf(PathStep.Key("messages"), PathStep.Index(0), PathStep.Key("content"))),
        ).isTrue()
        assertThat(
            path.matches(listOf(PathStep.Key("messages"), PathStep.Index(999), PathStep.Key("content"))),
        ).isTrue()
        // An index step cannot satisfy a key segment and vice versa.
        assertThat(
            path.matches(listOf(PathStep.Key("messages"), PathStep.Key("0"), PathStep.Key("content"))),
        ).isFalse()
    }

    @Test
    fun `nested array wildcards parse`() {
        val path = FieldPath.parse("matrix[][]")
        assertThat(
            path.matches(listOf(PathStep.Key("matrix"), PathStep.Index(1), PathStep.Index(2))),
        ).isTrue()
    }

    @Test
    fun `standalone brackets match top-level array elements`() {
        val path = FieldPath.parse("[].id")
        assertThat(path.matches(listOf(PathStep.Index(3), PathStep.Key("id")))).isTrue()
    }

    @Test
    fun `star matches any key at that level`() {
        val path = FieldPath.parse("metadata.*")
        assertThat(path.matches(listOf(PathStep.Key("metadata"), PathStep.Key("anything")))).isTrue()
        assertThat(path.matches(listOf(PathStep.Key("metadata"), PathStep.Index(0)))).isFalse()
    }

    @Test
    fun `prefix detection drives descent`() {
        val path = FieldPath.parse("messages[].tool_calls[].name")
        assertThat(path.isPrefixOf(listOf(PathStep.Key("messages")))).isTrue()
        assertThat(path.isPrefixOf(listOf(PathStep.Key("messages"), PathStep.Index(0)))).isTrue()
        assertThat(path.isPrefixOf(listOf(PathStep.Key("usage")))).isFalse()
        // A full match is not a strict prefix.
        assertThat(
            path.isPrefixOf(
                listOf(
                    PathStep.Key("messages"), PathStep.Index(0),
                    PathStep.Key("tool_calls"), PathStep.Index(0), PathStep.Key("name"),
                ),
            ),
        ).isFalse()
    }

    @Test
    fun `malformed expressions are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { FieldPath.parse("") }
        assertThrows(IllegalArgumentException::class.java) { FieldPath.parse("  ") }
        assertThrows(IllegalArgumentException::class.java) { FieldPath.parse("a..b") }
        assertThrows(IllegalArgumentException::class.java) { FieldPath.parse("a[0].b") }
        assertThrows(IllegalArgumentException::class.java) { FieldPath.parse("a[.b") }
    }

    @Test
    fun `equality is by expression`() {
        assertThat(FieldPath.parse("a.b")).isEqualTo(FieldPath.parse("a.b"))
        assertThat(FieldPath.parse("a.b")).isNotEqualTo(FieldPath.parse("a.c"))
    }
}
