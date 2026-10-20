package com.replypilot.domain.rules

/**
 * Runs user-supplied regular expressions safely: bounded pattern and input length, a rejection
 * heuristic for nested quantifiers, and a time budget enforced through the input CharSequence.
 */
object RegexGuard {
    const val MAX_PATTERN_LENGTH = 200
    const val MAX_INPUT_LENGTH = 2_000
    private const val TIME_BUDGET_MS = 50L
    private val NESTED_QUANTIFIER = Regex("""\([^)]*[+*][^)]*\)\s*[+*{]""")

    sealed interface Check {
        data object Valid : Check
        data class Invalid(val reason: String) : Check
    }

    fun check(pattern: String): Check = when {
        pattern.isBlank() -> Check.Invalid("Pattern is empty.")
        pattern.length > MAX_PATTERN_LENGTH -> Check.Invalid("Pattern is longer than $MAX_PATTERN_LENGTH characters.")
        NESTED_QUANTIFIER.containsMatchIn(pattern) -> Check.Invalid("Nested repetition like (a+)+ can freeze matching; simplify the pattern.")
        else -> runCatching { Regex(pattern) }.fold(
            onSuccess = { Check.Valid },
            onFailure = { Check.Invalid("Invalid pattern: ${it.message?.lineSequence()?.firstOrNull().orEmpty()}") },
        )
    }

    /** Returns null when the pattern is unsafe, invalid, or exceeded its time budget. */
    fun matches(pattern: String, input: String, caseSensitive: Boolean): Boolean? {
        if (check(pattern) != Check.Valid) return null
        val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        val regex = Regex(pattern, options)
        val bounded = DeadlineCharSequence(input.take(MAX_INPUT_LENGTH), System.nanoTime() + TIME_BUDGET_MS * 1_000_000)
        return try {
            regex.containsMatchIn(bounded)
        } catch (_: TimeoutException) {
            null
        }
    }

    private class TimeoutException : RuntimeException()

    private class DeadlineCharSequence(private val inner: CharSequence, private val deadline: Long) : CharSequence {
        override val length: Int get() = inner.length
        override fun get(index: Int): Char {
            if (System.nanoTime() > deadline) throw TimeoutException()
            return inner[index]
        }
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            DeadlineCharSequence(inner.subSequence(startIndex, endIndex), deadline)
        override fun toString(): String = inner.toString()
    }
}
