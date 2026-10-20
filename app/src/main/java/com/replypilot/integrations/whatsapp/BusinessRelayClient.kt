package com.replypilot.integrations.whatsapp

import com.replypilot.core.database.AppJson
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * Talks to a self-hosted relay that holds the WhatsApp Business Platform credentials.
 * The phone only holds a relay device token; Meta's access token never leaves the server.
 * Contract: docs/integrations/whatsapp-business-relay.md
 */
class BusinessRelayClient(private val baseUrl: String, private val deviceToken: String) {

    @Serializable
    data class SendRequest(val to: String, val text: String, val idempotencyKey: String, val templateName: String? = null)

    @Serializable
    data class SendResponse(val id: String)

    @Serializable
    data class StatusResponse(val id: String, val status: String, val detail: String? = null)

    @Serializable
    data class HealthResponse(val ok: Boolean, val phoneNumber: String? = null)

    sealed interface Result<out T> {
        data class Ok<T>(val value: T) : Result<T>
        data class Http(val code: Int, val message: String) : Result<Nothing>
        data class Network(val message: String) : Result<Nothing>
        /** 2xx with a body we couldn't read: the relay most likely accepted the request. */
        data class AcceptedUnreadable(val code: Int) : Result<Nothing>
    }

    suspend fun health(): Result<HealthResponse> = request("GET", "/v1/health", null) {
        AppJson.decodeFromString(HealthResponse.serializer(), it)
    }

    suspend fun send(request: SendRequest): Result<SendResponse> =
        request("POST", "/v1/messages", AppJson.encodeToString(SendRequest.serializer(), request)) {
            AppJson.decodeFromString(SendResponse.serializer(), it)
        }

    suspend fun status(id: String): Result<StatusResponse> = request("GET", "/v1/messages/${java.net.URLEncoder.encode(id, "UTF-8")}", null) {
        AppJson.decodeFromString(StatusResponse.serializer(), it)
    }

    private suspend fun <T> request(method: String, path: String, body: String?, parse: (String) -> T): Result<T> =
        withContext(Dispatchers.IO) {
            val url = runCatching { URL(baseUrl.trimEnd('/') + path) }.getOrElse { return@withContext Result.Network("Invalid relay URL") }
            if (url.protocol != "https") return@withContext Result.Network("The relay must use HTTPS")
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.requestMethod = method
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty("Authorization", "Bearer $deviceToken")
                connection.setRequestProperty("Accept", "application/json")
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader()?.use { it.readText().take(MAX_RESPONSE_CHARS) }.orEmpty()
                if (code in 200..299) {
                    runCatching<Result<T>> { Result.Ok(parse(text)) }.getOrElse { Result.AcceptedUnreadable(code) }
                } else {
                    Result.Http(code, text.take(200).ifBlank { "HTTP $code" })
                }
            } catch (e: IOException) {
                Result.Network(e.message ?: "Network error")
            } finally {
                connection.disconnect()
            }
        }

    private companion object {
        const val TIMEOUT_MS = 15_000
        const val MAX_RESPONSE_CHARS = 64_000
    }
}
