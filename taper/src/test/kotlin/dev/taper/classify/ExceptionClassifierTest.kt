package dev.taper.classify

import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonEncodingException
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Test

class ExceptionClassifierTest {

    private val classifier = ExceptionClassifier()

    // ---- HTTP status codes ----

    @Test
    fun `plain 4xx statuses are semantic`() {
        for (status in listOf(400, 401, 403, 404, 405, 409, 410, 413, 422, 451)) {
            assertThat(classifier.classify(AgentFailure(httpStatus = status)))
                .isEqualTo(FailureCategory.SEMANTIC)
        }
    }

    @Test
    fun `5xx statuses are transient`() {
        for (status in listOf(500, 502, 503, 504, 599)) {
            assertThat(classifier.classify(AgentFailure(httpStatus = status)))
                .isEqualTo(FailureCategory.TRANSIENT)
        }
    }

    @Test
    fun `edge case - 408 request timeout is 4xx by number but transient by nature`() {
        assertThat(classifier.classify(AgentFailure(httpStatus = 408)))
            .isEqualTo(FailureCategory.TRANSIENT)
    }

    @Test
    fun `edge case - 429 rate limit is 4xx by number but transient by nature`() {
        assertThat(classifier.classify(AgentFailure(httpStatus = 429)))
            .isEqualTo(FailureCategory.TRANSIENT)
    }

    @Test
    fun `edge case - 425 too early is transient`() {
        assertThat(classifier.classify(AgentFailure(httpStatus = 425)))
            .isEqualTo(FailureCategory.TRANSIENT)
    }

    @Test
    fun `edge case - 501 not implemented is 5xx by number but semantic by nature`() {
        assertThat(classifier.classify(AgentFailure(httpStatus = 501)))
            .isEqualTo(FailureCategory.SEMANTIC)
    }

    // ---- Exception types ----

    @Test
    fun `network environment exceptions are transient`() {
        val transientExceptions = listOf(
            SocketTimeoutException("read timed out"),
            ConnectException("connection refused"),
            UnknownHostException("api.example.test"), // DNS failure
            SocketException("Connection reset"),
            EOFException("unexpected end of stream"),
            IOException("canceled"),
        )
        for (e in transientExceptions) {
            assertThat(classifier.classify(e)).isEqualTo(FailureCategory.TRANSIENT)
        }
    }

    @Test
    fun `payload and protocol exceptions are semantic`() {
        val semanticExceptions = listOf(
            JsonEncodingException("malformed JSON at path \$.tool_calls"),
            JsonDataException("expected an array but was STRING"),
            ProtocolException("unexpected status line"),
        )
        for (e in semanticExceptions) {
            assertThat(classifier.classify(e)).isEqualTo(FailureCategory.SEMANTIC)
        }
    }

    @Test
    fun `wrapped exceptions are classified by their cause`() {
        val wrapped = RuntimeException("sync failed", JsonDataException("invalid tool call"))
        assertThat(classifier.classify(wrapped)).isEqualTo(FailureCategory.SEMANTIC)
    }

    @Test
    fun `deeply wrapped transient causes are still found`() {
        val wrapped = RuntimeException(RuntimeException(SocketTimeoutException("timeout")))
        assertThat(classifier.classify(wrapped)).isEqualTo(FailureCategory.TRANSIENT)
    }

    @Test
    fun `pathological cause cycles terminate`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        // No opinion from any rule → fallback, but crucially: no infinite loop.
        assertThat(classifier.classify(a)).isEqualTo(FailureCategory.TRANSIENT)
    }

    // ---- Error body shape ----

    @Test
    fun `anthropic style invalid_request_error body is semantic`() {
        val body = """{"type": "error", "error": {"type": "invalid_request_error",
            "message": "tools.0.input_schema: required field missing"}}"""
        assertThat(classifier.classify(AgentFailure(responseBody = body)))
            .isEqualTo(FailureCategory.SEMANTIC)
    }

    @Test
    fun `anthropic style overloaded_error body is transient`() {
        val body = """{"type": "error", "error": {"type": "overloaded_error", "message": "..."}}"""
        assertThat(classifier.classify(AgentFailure(responseBody = body)))
            .isEqualTo(FailureCategory.TRANSIENT)
    }

    @Test
    fun `openai style error code is recognised`() {
        val body = """{"error": {"code": "context_length_exceeded", "message": "too long"}}"""
        assertThat(classifier.classify(AgentFailure(responseBody = body)))
            .isEqualTo(FailureCategory.SEMANTIC)
    }

    @Test
    fun `body shape outranks status - rate limit body with 400 status is transient`() {
        val failure = AgentFailure(
            httpStatus = 400,
            responseBody = """{"error": {"type": "rate_limit_error", "message": "slow down"}}""",
        )
        assertThat(classifier.classify(failure)).isEqualTo(FailureCategory.TRANSIENT)
    }

    @Test
    fun `body shape outranks status - invalid request body with 503 status is semantic`() {
        val failure = AgentFailure(
            httpStatus = 503,
            responseBody = """{"error": {"type": "invalid_request_error", "message": "bad tool"}}""",
        )
        assertThat(classifier.classify(failure)).isEqualTo(FailureCategory.SEMANTIC)
    }

    @Test
    fun `non-json body falls through to the status rule`() {
        val failure = AgentFailure(httpStatus = 502, responseBody = "<html>Bad Gateway</html>")
        assertThat(classifier.classify(failure)).isEqualTo(FailureCategory.TRANSIENT)
    }

    @Test
    fun `unknown error type in body falls through to the status rule`() {
        val failure = AgentFailure(
            httpStatus = 403,
            responseBody = """{"error": {"type": "brand_new_error_nobody_mapped"}}""",
        )
        assertThat(classifier.classify(failure)).isEqualTo(FailureCategory.SEMANTIC)
    }

    // ---- Fallback + extensibility ----

    @Test
    fun `no signal at all falls back to transient by default`() {
        assertThat(classifier.classify(AgentFailure())).isEqualTo(FailureCategory.TRANSIENT)
        assertThat(classifier.classify(IllegalStateException("???")))
            .isEqualTo(FailureCategory.TRANSIENT)
    }

    @Test
    fun `fallback is configurable`() {
        val strict = ExceptionClassifier(fallback = FailureCategory.SEMANTIC)
        assertThat(strict.classify(AgentFailure())).isEqualTo(FailureCategory.SEMANTIC)
    }

    @Test
    fun `custom rules prepended to the chain win over defaults`() {
        // A team that treats 404 as transient (eventually-consistent backend).
        val custom = ClassificationRule { failure ->
            if (failure.httpStatus == 404) FailureCategory.TRANSIENT else null
        }
        val classifier = ExceptionClassifier(
            rules = listOf(custom) + ExceptionClassifier.DEFAULT_RULES,
        )
        assertThat(classifier.classify(AgentFailure(httpStatus = 404)))
            .isEqualTo(FailureCategory.TRANSIENT)
        // Everything else still follows the default chain.
        assertThat(classifier.classify(AgentFailure(httpStatus = 400)))
            .isEqualTo(FailureCategory.SEMANTIC)
    }

    @Test
    fun `custom body type mapping extends the shape rule`() {
        val rule = ErrorBodyShapeRule(
            typeMapping = ErrorBodyShapeRule.DEFAULT_TYPE_MAPPING +
                ("my_provider_backpressure" to FailureCategory.TRANSIENT),
        )
        val classifier = ExceptionClassifier(rules = listOf(rule))
        val failure = AgentFailure(
            responseBody = """{"error": {"type": "my_provider_backpressure"}}""",
        )
        assertThat(classifier.classify(failure)).isEqualTo(FailureCategory.TRANSIENT)
    }
}
