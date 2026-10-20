package com.replypilot.domain.scheduling

import com.replypilot.domain.common.HOUR_MS
import com.replypilot.domain.common.MINUTE_MS
import com.replypilot.domain.model.FailureCode

/** Bounded exponential backoff: 1, 2, 4, 8… minutes, capped at one hour. Permanent failures never retry. */
object RetryPolicy {
    private const val BASE_DELAY_MS = MINUTE_MS
    private const val MAX_DELAY_MS = HOUR_MS

    fun shouldRetry(code: FailureCode, attempts: Int, maxAttempts: Int): Boolean =
        !code.permanent && attempts < maxAttempts

    fun delayFor(attempt: Int): Long {
        val exponent = (attempt - 1).coerceIn(0, 16)
        return (BASE_DELAY_MS shl exponent).coerceAtMost(MAX_DELAY_MS)
    }
}
