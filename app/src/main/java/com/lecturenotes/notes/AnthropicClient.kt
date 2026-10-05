package com.lecturenotes.notes

import org.json.JSONArray
import org.json.JSONObject

/** Claude via the Anthropic Messages API. */
class AnthropicClient(private val config: ProviderConfig) : LlmClient {
    private val base = config.baseUrl.trimEnd('/')

    private fun headers() = mapOf(
        "x-api-key" to (config.apiKey ?: ""),
        "anthropic-version" to "2023-06-01",
    )

    override suspend fun listModels(): List<ModelInfo> {
        val res = http("GET", "$base/models?limit=100", headers(), readTimeoutMs = 20_000)
        checkErrors(res)
        val data = JSONObject(res.body).optJSONArray("data") ?: JSONArray()
        return (0 until data.length()).map { data.getJSONObject(it).getString("id") }
            .map { ModelInfo(it, CLAUDE_CONTEXT, free = false) }
    }

    override suspend fun complete(request: LlmRequest): LlmReply {
        val body = JSONObject().apply {
            put("model", config.model)
            put("max_tokens", request.maxTokens)
            put("temperature", 0.2)
            put("system", request.system)
            put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", request.user)))
        }
        val res = http("POST", "$base/messages", headers(), body.toString())
        checkErrors(res)
        val json = JSONObject(res.body)
        if (json.optString("stop_reason") == "max_tokens") throw LlmException.Truncated()
        val content = json.getJSONArray("content")
        val text = (0 until content.length()).map { content.getJSONObject(it) }
            .filter { it.optString("type") == "text" }
            .joinToString("") { it.optString("text") }
        return LlmReply(text)
    }

    private fun checkErrors(res: HttpResult) {
        if (res.code in 200..299) return
        val message = runCatching { JSONObject(res.body).getJSONObject("error").optString("message") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: res.body.take(300)
        when {
            res.code == 401 || res.code == 403 -> throw LlmException.Auth("The API key was rejected ($message)")
            res.code == 404 -> throw LlmException.ModelNotFound(message)
            res.code == 413 || looksTooLarge(message) -> throw LlmException.TooLarge(message, null)
            res.code == 429 || res.code == 529 -> throw LlmException.RateLimited(parseDurationMs(res.headers["retry-after"]) ?: 20_000)
            else -> throw LlmException.Server(res.code, message)
        }
    }

    companion object {
        const val CLAUDE_CONTEXT = 200_000
    }
}
