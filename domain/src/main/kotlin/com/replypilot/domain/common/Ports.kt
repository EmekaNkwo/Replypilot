package com.replypilot.domain.common

import java.time.ZoneId
import java.util.UUID

interface Clock {
    fun now(): Long
    fun zone(): ZoneId
}

object SystemClock : Clock {
    override fun now(): Long = System.currentTimeMillis()
    override fun zone(): ZoneId = ZoneId.systemDefault()
}

interface IdGenerator {
    fun next(): String
}

object UuidGenerator : IdGenerator {
    override fun next(): String = UUID.randomUUID().toString()
}

const val MINUTE_MS: Long = 60_000L
const val HOUR_MS: Long = 60 * MINUTE_MS
const val DAY_MS: Long = 24 * HOUR_MS
