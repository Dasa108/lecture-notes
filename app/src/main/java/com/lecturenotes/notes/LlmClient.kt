package com.lecturenotes.notes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class ModelInfo(val id: String, val contextWindow: Int?, val free: Boolean)

data class LlmRequest(val system: String, val user: String, val maxTokens: Int, val json: Boolean = true)

/** [tokensRemaining]/[resetMs]: the provider's per-minute budget after this call, when reported. */
data class LlmReply(val text: String, val tokensRemaining: Int? = null, val resetMs: Long? = null)

sealed class LlmException(message: String) : IOException(message) {
    class Auth(message: String) : LlmException(message)
    class ModelNotFound(message: String) : LlmException(message)
    /** The request is bigger than the model or rate limit allows. [limitTokens] if the provider said. */
    class TooLarge(message: String, val limitTokens: Int?) : LlmException(message)
    /** The reply was cut off at max_tokens. */
    class Truncated : LlmException("The AI's reply was cut off")
    class RateLimited(val retryAfterMs: Long) : LlmException("Rate limited")
    class Server(val code: Int, message: String) : LlmException(message)
}

interface LlmClient {
    suspend fun listModels(): List<ModelInfo>
    suspend fun complete(request: LlmRequest): LlmReply

    companion object {
        fun create(config: ProviderConfig): LlmClient = when (config.preset.kind) {
            ApiKind.OPENAI_COMPATIBLE -> OpenAiCompatibleClient(config)
            ApiKind.ANTHROPIC -> AnthropicClient(config)
        }
    }
}

internal data class HttpResult(val code: Int, val body: String, val headers: Map<String, String>)

internal suspend fun http(
    method: String,
    url: String,
    headers: Map<String, String>,
    body: String? = null,
    readTimeoutMs: Int = 180_000,
): HttpResult = withContext(Dispatchers.IO) {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = 15_000
        readTimeout = readTimeoutMs
        headers.forEach { (k, v) -> setRequestProperty(k, v) }
        if (body != null) {
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
    }
    try {
        if (body != null) conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        val hdrs = conn.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() }.mapValues { it.value.firstOrNull() ?: "" }
        HttpResult(code, text, hdrs)
    } finally {
        conn.disconnect()
    }
}

/** Parses durations like "7.66s", "2m59.56s", "120ms" (Groq's rate-limit reset headers). */
internal fun parseDurationMs(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    value.trim().toDoubleOrNull()?.let { return (it * 1000).toLong() } // plain seconds (retry-after)
    var total = 0.0
    var matched = false
    Regex("""([\d.]+)(ms|h|m|s)""").findAll(value).forEach { m ->
        matched = true
        val n = m.groupValues[1].toDouble()
        total += when (m.groupValues[2]) {
            "h" -> n * 3_600_000
            "m" -> n * 60_000
            "s" -> n * 1000
            else -> n
        }
    }
    return if (matched) total.toLong() else null
}

/** Extracts a token limit from errors like "... TPM: Limit 6000, Requested 13421". */
internal fun parseTokenLimit(message: String): Int? =
    Regex("""Limit (\d+), Requested (\d+)""").find(message)?.groupValues?.get(1)?.toIntOrNull()
        ?: Regex("""maximum context length is (\d+)""").find(message)?.groupValues?.get(1)?.toIntOrNull()

internal fun looksTooLarge(message: String): Boolean {
    val m = message.lowercase()
    return "too large" in m || "context length" in m || "context_length" in m || "too long" in m ||
        "maximum context" in m || "reduce the length" in m
}
