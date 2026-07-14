package dev.taper.parser

/**
 * A compiled path expression selecting fields inside a JSON document.
 *
 * Syntax (dot-separated segments):
 *  - `key`        matches an object member named `key`
 *  - `key[]`      matches every element of the array held by `key`
 *  - `*`          matches any object member at that level
 *  - `[]`         (standalone) matches every element of an array at that level,
 *                 e.g. `[].id` for a top-level array of objects
 *
 * Examples:
 *  - `usage.total_tokens`
 *  - `messages[].content`
 *  - `messages[].tool_calls[].name`
 *  - `metadata.*`
 */
class FieldPath private constructor(
    val expression: String,
    internal val segments: List<Segment>,
) {

    internal sealed interface Segment {
        data class Key(val name: String) : Segment
        data object AnyIndex : Segment
        data object AnyKey : Segment
    }

    /** True when [stack] (the parser's current location) exactly matches this path. */
    internal fun matches(stack: List<PathStep>): Boolean {
        if (stack.size != segments.size) return false
        for (i in segments.indices) {
            if (!segmentMatches(segments[i], stack[i])) return false
        }
        return true
    }

    /**
     * True when [stack] is a strict prefix of this path, i.e. the parser should
     * keep descending because a match may exist deeper in the document.
     */
    internal fun isPrefixOf(stack: List<PathStep>): Boolean {
        if (stack.size >= segments.size) return false
        for (i in stack.indices) {
            if (!segmentMatches(segments[i], stack[i])) return false
        }
        return true
    }

    private fun segmentMatches(segment: Segment, step: PathStep): Boolean = when (segment) {
        is Segment.Key -> step is PathStep.Key && step.name == segment.name
        Segment.AnyIndex -> step is PathStep.Index
        Segment.AnyKey -> step is PathStep.Key
    }

    override fun toString(): String = expression
    override fun equals(other: Any?): Boolean = other is FieldPath && other.expression == expression
    override fun hashCode(): Int = expression.hashCode()

    companion object {
        /**
         * Parses [expression] into a [FieldPath].
         * @throws IllegalArgumentException on malformed expressions.
         */
        fun parse(expression: String): FieldPath {
            require(expression.isNotBlank()) { "Field path must not be blank" }
            val segments = mutableListOf<Segment>()
            for (rawToken in expression.split('.')) {
                var token = rawToken
                require(token.isNotEmpty()) { "Empty segment in field path '$expression'" }
                // Peel trailing "[]" markers: "key[]" or "key[][]" (array of arrays).
                var arrayDepth = 0
                while (token.endsWith("[]")) {
                    token = token.dropLast(2)
                    arrayDepth++
                }
                when {
                    token == "*" -> segments += Segment.AnyKey
                    token.isEmpty() -> require(arrayDepth > 0) {
                        "Empty segment in field path '$expression'"
                    }
                    else -> {
                        require(!token.contains('[') && !token.contains(']')) {
                            "Unsupported bracket syntax in '$rawToken'; only trailing '[]' is allowed"
                        }
                        segments += Segment.Key(token)
                    }
                }
                repeat(arrayDepth) { segments += Segment.AnyIndex }
            }
            return FieldPath(expression, segments)
        }
    }
}

/** One step of the parser's current position inside the document. */
internal sealed interface PathStep {
    data class Key(val name: String) : PathStep
    data class Index(val index: Int) : PathStep
}

/** Renders a concrete document location such as `messages[3].content`. */
internal fun List<PathStep>.render(): String = buildString {
    for (step in this@render) {
        when (step) {
            is PathStep.Key -> {
                if (isNotEmpty()) append('.')
                append(step.name)
            }
            is PathStep.Index -> append('[').append(step.index).append(']')
        }
    }
}
