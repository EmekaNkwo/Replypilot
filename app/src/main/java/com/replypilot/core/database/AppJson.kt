package com.replypilot.core.database

import kotlinx.serialization.json.Json

/** Shared JSON configuration for structured columns and exports. */
val AppJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "type"
}
