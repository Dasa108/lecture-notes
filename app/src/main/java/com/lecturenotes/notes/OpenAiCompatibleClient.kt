package com.lecturenotes.notes

import org.json.JSONArray
import org.json.JSONObject

/** Groq, OpenRouter, Cerebras, Ollama, and any other /v1/chat/completions server. */
class OpenAiCompatibleClient(private val config: ProviderConfig) : LlmClient {
    private val base = config.baseUrl.trimEnd('/')
    @Volatile private var jsonModeSupported = true

    private fun headers() = buildMap {
        config.apiKey?.takeIf { it.isNotBlank() }?.let { put("Authorization", "Bearer $it") }
        if (config.preset == ProviderPreset.OPENROUTER) put("X-Title", "Lecture Notes")
    }

    override suspend fun listModels(): List<ModelInfo> {
        val res = http("GET", "$base/models", headers(), readTimeoutMs = 20_000)
        checkErrors(res)
        val data = JSONObject(res.body).optJSONArray("data") ?: JSONArray()
        return (0 until data.length()).map { data.getJSONObject(it) }
            .filter { it.optBoolean("active", true) }
            .map { m ->
                val id = m.getString("id")
                val ctx = m.optInt("context_window", 0).takeIf { it > 0 } ?: m.optInt("context_length", 0).takeIf { it > 0 }
                val pricing = m.optJSONObject("pricing")
                val free = config.preset.isFree && config.preset != ProviderPreset.OPENROUTER ||
                    id.endsWith(":free") || (pricing != null && pricing.optString("prompt") == "0" && pricing.optString("completion") == "0")
                ModelInfo(id, ctx, free)
            }
            .filterNot { m -> NON_CHAT.any { it in m.id.lowercase() } }
    }

    override suspend fun complete(request: LlmRequest): LlmReply {
        val body = JSONObject().apply {
            put("model", config.model)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", request.system))
                put(JSONObject().put("role", "user").put("content", request.user))
            })
            put("max_tokens", request.maxTokens)
            put("temperature", 0.2)
            if (request.json && jsonModeSupported) put("response_format", JSONObject().put("type", "json_object"))
        }
        val res = http("POST", "$base/chat/completions", headers(), body.toString())
        if (res.code == 400 && request.json && jsonModeSupported && "response_format" in res.body) {
            // Some models/servers reject JSON mode; fall back to prompt-only JSON.
            jsonModeSupported = false
            return complete(request)
        }
        checkErrors(res)
        val choice = JSONObject(res.body).getJSONArray("choices").getJSONObject(0)
        if (choice.optString("finish_reason") == "length") throw LlmException.Truncated()
        val text = choice.getJSONObject("message").optString("content")
            .replace(THINK, "") // reasoning models may inline their thinking
        return LlmReply(
            text = text,
            tokensRemaining = res.headers["x-ratelimit-remaining-tokens"]?.toIntOrNull(),
            resetMs = parseDurationMs(res.headers["x-ratelimit-reset-tokens"]),
        )
    }

    private fun checkErrors(res: HttpResult) {
        if (res.code in 200..299) return
        val message = runCatching { JSONObject(res.body).getJSONObject("error").optString("message") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: res.body.take(300)
        when {
            res.code == 401 || res.code == 403 -> throw LlmException.Auth("The API key was rejected ($message)")
            res.code == 404 -> throw LlmException.ModelNotFound(message)
            res.code == 413 || looksTooLarge(message) -> throw LlmException.TooLarge(message, parseTokenLimit(message))
            res.code == 429 -> throw LlmException.RateLimited(
                parseDurationMs(res.headers["retry-after"]) ?: parseDurationMs(res.headers["x-ratelimit-reset-tokens"]) ?: 20_000,
            )
            else -> throw LlmException.Server(res.code, message)
        }
    }

    private companion object {
        val THINK = Regex("(?s)<think>.*?</think>")
        val NON_CHAT = listOf("whisper", "tts", "guard", "embed", "moderation", "playai", "orpheus", "prompt-guard", "image")
    }
}
