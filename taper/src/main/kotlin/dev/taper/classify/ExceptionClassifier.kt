package dev.taper.classify

import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonEncodingException
import dev.taper.parser.TaperParser
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException

/**
 * How a failure should be handled by orchestration code.
 */
enum class FailureCategory {
    /**
     * The request itself is wrong — malformed body, invalid function/tool call,
     * content-policy rejection, bad auth. Retrying the identical request can
     * never succeed. Halt and surface to the caller.
     */
    SEMANTIC,

    /**
     * The environment failed — timeout, connection reset, DNS, server overload.
     * The identical request may well succeed later. Queue for retry.
     */
    TRANSIENT,
}

/**
 * Everything the classifier may inspect about a failure. All fields optional:
 * a pure transport failure has only [exception]; an HTTP-level failure has
 * [httpStatus] and usually [responseBody].
 *
 * @param responseBody the error response body, if any. Callers should cap what
 *   they retain here (error bodies are small; do not hand the classifier a
 *   multi-megabyte success payload).
 */
data class AgentFailure(
    val httpStatus: Int? = null,
    val exception: Throwable? = null,
    val responseBody: String? = null,
)

/**
 * One link in the classification chain. Rules are consulted in order; the first
 * non-null answer wins (chain of responsibility). Return null to pass the
 * decision to the next rule.
 */
fun interface ClassificationRule {
    fun classify(failure: AgentFailure): FailureCategory?
}

/**
 * Rules-based failure classifier.
 *
 * WHY RULES, NOT ML (v1 design decision):
 * The discriminating signals for retryability — status code, exception type,
 * provider error `type` strings — are near-deterministic, documented contracts,
 * not statistical patterns. A rule table is auditable, testable, ships no model
 * weights, and adds zero inference latency or battery cost. A learned classifier
 * would add risk (silent misclassification drops user data) without adding
 * information the rules don't already see.
 *
 * SCOPED PATH TO AN ML v2 (not implemented here):
 * [AgentFailure] is already a feature record. A v2 could featurise it
 * (status bucket, exception class one-hot, body token n-grams) and train
 * logistic regression offline on labelled retry outcomes, shipping weights as a
 * few KB of constants evaluated on-device — no runtime framework needed. It
 * would slot in as one more [ClassificationRule] placed *after* the
 * deterministic rules, so hard contracts always win and the model only breaks
 * ties on genuinely ambiguous failures. See README "Future work".
 *
 * Extensibility: pass custom rules first, keep the defaults as fallback:
 * ```
 * val classifier = ExceptionClassifier(
 *     rules = listOf(MyProviderRule()) + ExceptionClassifier.DEFAULT_RULES,
 * )
 * ```
 */
class ExceptionClassifier(
    private val rules: List<ClassificationRule> = DEFAULT_RULES,
    /**
     * Used when no rule has an opinion. Defaults to [FailureCategory.TRANSIENT]:
     * unknown failures are more often infrastructure than contract violations,
     * retries are bounded by the sync queue's attempt cap (bounded waste), while
     * wrongly halting on a transient failure permanently drops the user's update
     * (unbounded harm). Override if your retry budget is tighter than your data.
     */
    private val fallback: FailureCategory = FailureCategory.TRANSIENT,
) {

    fun classify(failure: AgentFailure): FailureCategory {
        for (rule in rules) {
            rule.classify(failure)?.let { return it }
        }
        return fallback
    }

    /** Convenience for pure-exception failures. */
    fun classify(exception: Throwable): FailureCategory =
        classify(AgentFailure(exception = exception))

    companion object {
        /**
         * Default chain, most-specific signal first:
         * body shape (provider explicitly told us) → HTTP status → exception type.
         */
        val DEFAULT_RULES: List<ClassificationRule> = listOf(
            ErrorBodyShapeRule(),
            HttpStatusRule(),
            ExceptionTypeRule(),
        )
    }
}

/**
 * Classifies by HTTP status code.
 *
 * Edge cases handled deliberately:
 *  - 408 Request Timeout, 425 Too Early, 429 Too Many Requests are 4xx by number
 *    but transient by nature (the identical request can succeed later).
 *  - 501 Not Implemented is 5xx by number but semantic by nature (the server
 *    will never learn the method by retrying).
 */
class HttpStatusRule : ClassificationRule {
    override fun classify(failure: AgentFailure): FailureCategory? {
        val status = failure.httpStatus ?: return null
        return when (status) {
            408, 425, 429 -> FailureCategory.TRANSIENT
            in 400..499 -> FailureCategory.SEMANTIC
            501 -> FailureCategory.SEMANTIC
            in 500..599 -> FailureCategory.TRANSIENT
            else -> null // 1xx/2xx/3xx: not a failure signal this rule understands
        }
    }
}

/**
 * Classifies by exception type, walking the cause chain so wrapped exceptions
 * (e.g. an okhttp `IOException` wrapping a `SocketTimeoutException`) still match.
 */
class ExceptionTypeRule : ClassificationRule {
    override fun classify(failure: AgentFailure): FailureCategory? {
        var current: Throwable? = failure.exception ?: return null
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            classifyOne(current)?.let { return it }
            current = current.cause
            depth++
        }
        return null
    }

    private fun classifyOne(t: Throwable): FailureCategory? = when (t) {
        // Request/payload is structurally wrong — retrying reproduces it.
        is JsonEncodingException -> FailureCategory.SEMANTIC
        is JsonDataException -> FailureCategory.SEMANTIC
        is ProtocolException -> FailureCategory.SEMANTIC
        is UnknownServiceException -> FailureCategory.SEMANTIC

        // Environment failures — connectivity can come back.
        is SocketTimeoutException -> FailureCategory.TRANSIENT
        is InterruptedIOException -> FailureCategory.TRANSIENT
        is ConnectException -> FailureCategory.TRANSIENT
        is NoRouteToHostException -> FailureCategory.TRANSIENT
        is PortUnreachableException -> FailureCategory.TRANSIENT
        is UnknownHostException -> FailureCategory.TRANSIENT // DNS failure
        is SocketException -> FailureCategory.TRANSIENT // includes connection reset
        is EOFException -> FailureCategory.TRANSIENT // truncated response
        is IOException -> FailureCategory.TRANSIENT // conservative catch-all for I/O

        else -> null
    }

    private companion object {
        const val MAX_CAUSE_DEPTH = 10 // guards against pathological cause cycles
    }
}

/**
 * Classifies by the *shape* of a JSON error body, using Taper's own streaming
 * parser to pull out the provider's error type without materialising the body.
 *
 * Defaults cover the documented Anthropic-style error envelope
 * `{"type": "error", "error": {"type": "...", "message": "..."}}` plus the
 * OpenAI-style `{"error": {"type"/"code": "..."}}`. Extend or replace the
 * mapping for other providers via the constructor.
 */
class ErrorBodyShapeRule(
    private val typeMapping: Map<String, FailureCategory> = DEFAULT_TYPE_MAPPING,
) : ClassificationRule {

    private val parser = TaperParser()

    override fun classify(failure: AgentFailure): FailureCategory? {
        val body = failure.responseBody ?: return null
        if (body.isBlank()) return null
        val extracted = try {
            parser.parse(body.byteInputStream(), FIELDS)
        } catch (_: Exception) {
            return null // not JSON, or not a shape we recognise — no opinion
        }
        for (field in FIELDS) {
            val type = extracted.firstString(field) ?: continue
            typeMapping[type]?.let { return it }
        }
        return null
    }

    companion object {
        private val FIELDS = setOf("error.type", "error.code", "type")

        val DEFAULT_TYPE_MAPPING: Map<String, FailureCategory> = mapOf(
            // Anthropic-style error types (docs.anthropic.com → API errors)
            "invalid_request_error" to FailureCategory.SEMANTIC,
            "authentication_error" to FailureCategory.SEMANTIC,
            "permission_error" to FailureCategory.SEMANTIC,
            "not_found_error" to FailureCategory.SEMANTIC,
            "request_too_large" to FailureCategory.SEMANTIC,
            "rate_limit_error" to FailureCategory.TRANSIENT,
            "api_error" to FailureCategory.TRANSIENT,
            "overloaded_error" to FailureCategory.TRANSIENT,
            // OpenAI-style additions
            "insufficient_quota" to FailureCategory.SEMANTIC,
            "invalid_api_key" to FailureCategory.SEMANTIC,
            "context_length_exceeded" to FailureCategory.SEMANTIC,
            "content_policy_violation" to FailureCategory.SEMANTIC,
            "server_error" to FailureCategory.TRANSIENT,
        )
    }
}
