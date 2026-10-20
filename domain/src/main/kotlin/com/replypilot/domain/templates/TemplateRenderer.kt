package com.replypilot.domain.templates

/**
 * Renders `{{name}}` and `{{name|fallback}}` placeholders.
 * Anything that cannot be resolved is left in place and reported, so callers can refuse to send it.
 */
object TemplateRenderer {
    private val PLACEHOLDER = Regex("""\{\{\s*([A-Za-z][A-Za-z0-9_]*)\s*(?:\|([^}]*))?\}\}""")
    const val MALFORMED = "malformed placeholder"

    data class Result(val text: String, val unresolved: Set<String>) {
        val isComplete: Boolean get() = unresolved.isEmpty()
    }

    fun variablesIn(body: String): Set<String> =
        PLACEHOLDER.findAll(body).map { it.groupValues[1] }.toSet()

    fun render(body: String, variables: Map<String, String>): Result {
        val unresolved = linkedSetOf<String>()
        val text = PLACEHOLDER.replace(body) { match ->
            val name = match.groupValues[1]
            val fallback = match.groups[2]?.value?.trim()
            val value = variables[name]?.takeIf { it.isNotBlank() }
            when {
                value != null -> value
                !fallback.isNullOrEmpty() -> fallback
                else -> {
                    unresolved += name
                    match.value
                }
            }
        }
        // Malformed braces (e.g. "{{firstName") would otherwise reach the recipient verbatim.
        val remainder = PLACEHOLDER.replace(text, "")
        if (remainder.contains("{{") || remainder.contains("}}")) unresolved += MALFORMED
        return Result(text, unresolved)
    }
}

/** Variables the app knows how to fill, with sample values for previews. */
object KnownVariables {
    data class Variable(val name: String, val description: String, val sample: String)

    val all: List<Variable> = listOf(
        Variable("firstName", "Contact first name", "Ada"),
        Variable("lastName", "Contact last name", "Okafor"),
        Variable("fullName", "Contact full name", "Ada Okafor"),
        Variable("businessName", "Your business name (Settings)", "Brightside Studio"),
        Variable("supportHours", "Your support hours (Settings)", "Mon–Fri, 9am–5pm"),
        Variable("appointmentDate", "Appointment date (contact field)", "15 October 2026"),
        Variable("appointmentTime", "Appointment time (contact field)", "9:00 AM"),
    )

    val samples: Map<String, String> = all.associate { it.name to it.sample }
    val names: Set<String> = all.map { it.name }.toSet()
}
