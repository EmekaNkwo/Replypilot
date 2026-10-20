package com.replypilot.core.accessibility

/**
 * Chat names as messaging apps show them. Headers shorten long names with "…" ("Ezenwanyi & Her Billionaires…"), and
 * that shortened form is what ReplyPilot reads when a chat is picked, so a name ending in "…" matches by its start.
 */
object ChatNames {
    private val ELLIPSES = listOf("…", "...")

    fun isShortened(name: String): Boolean = ELLIPSES.any { name.trim().endsWith(it) }

    /** The searchable part: the name without a trailing ellipsis. */
    fun searchable(name: String): String = ELLIPSES.fold(name.trim()) { acc, e -> acc.removeSuffix(e) }.trim()

    /** Same chat, allowing either side to be a shortened form of the other. */
    fun matches(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        val x = a.trim()
        val y = b.trim()
        if (x.equals(y, ignoreCase = true)) return true
        return prefixMatch(x, y) || prefixMatch(y, x)
    }

    private fun prefixMatch(shortened: String, full: String): Boolean {
        if (!isShortened(shortened)) return false
        val start = searchable(shortened)
        return start.length >= MIN_PREFIX && full.startsWith(start, ignoreCase = true)
    }

    private const val MIN_PREFIX = 4
}
