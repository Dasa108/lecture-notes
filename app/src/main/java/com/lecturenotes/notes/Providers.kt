package com.lecturenotes.notes

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ApiKind { OPENAI_COMPATIBLE, ANTHROPIC }

/** Built-in providers. All OpenAI-compatible ones share one client. */
enum class ProviderPreset(
    val id: String,
    val label: String,
    val defaultBaseUrl: String,
    val kind: ApiKind,
    val needsKey: Boolean,
    val isFree: Boolean,
    val keyUrl: String?,
    val blurb: String,
) {
    GROQ(
        "groq", "Groq", "https://api.groq.com/openai/v1", ApiKind.OPENAI_COMPATIBLE, true, true,
        "https://console.groq.com/keys",
        "Free, no card needed. Fast open models (Llama, Qwen, GPT-OSS). Per-minute limits; the app paces itself.",
    ),
    OPENROUTER(
        "openrouter", "OpenRouter", "https://openrouter.ai/api/v1", ApiKind.OPENAI_COMPATIBLE, true, true,
        "https://openrouter.ai/keys",
        "Many models; the ones marked “free” cost nothing but have daily limits.",
    ),
    CEREBRAS(
        "cerebras", "Cerebras", "https://api.cerebras.ai/v1", ApiKind.OPENAI_COMPATIBLE, true, true,
        "https://cloud.cerebras.ai",
        "Free tier with open models and per-minute limits.",
    ),
    OLLAMA(
        "ollama", "Ollama on your computer", "http://192.168.1.2:11434/v1", ApiKind.OPENAI_COMPATIBLE, false, true,
        "https://ollama.com",
        "Free and private: runs on your own PC. The phone must be on the same Wi-Fi.",
    ),
    CUSTOM(
        "custom", "Other (OpenAI-compatible)", "", ApiKind.OPENAI_COMPATIBLE, false, false, null,
        "Any server with an OpenAI-style /v1/chat/completions endpoint.",
    ),
    CLAUDE(
        "claude", "Claude (Anthropic)", "https://api.anthropic.com/v1", ApiKind.ANTHROPIC, true, false,
        "https://console.anthropic.com/settings/keys",
        "Paid, pay-as-you-go. The best notes.",
    ),
    ;

    companion object {
        fun byId(id: String?) = entries.firstOrNull { it.id == id }
    }
}

/** The provider the user has set up for notes. */
data class ProviderConfig(
    val preset: ProviderPreset,
    val baseUrl: String,
    val apiKey: String?,
    val model: String?,
    /** Tokens the model can read+write in one request, from the provider's model list. */
    val contextWindow: Int,
) {
    val isReady: Boolean get() = !model.isNullOrBlank() && baseUrl.isNotBlank() && (!preset.needsKey || !apiKey.isNullOrBlank())
    val displayName: String get() = "${preset.label}${model?.let { " · $it" } ?: ""}"
}

class ProviderSettings(context: Context) {
    private val prefs = context.getSharedPreferences("notes_ai", Context.MODE_PRIVATE)
    private val secrets = SecretStore(context)

    private val _config = MutableStateFlow(load())
    val config: StateFlow<ProviderConfig> = _config.asStateFlow()

    /** The saved settings for a specific preset (each preset remembers its own key/model). */
    fun configFor(preset: ProviderPreset) = ProviderConfig(
        preset = preset,
        baseUrl = prefs.getString("${preset.id}.baseUrl", null) ?: preset.defaultBaseUrl,
        apiKey = secrets.get("${preset.id}.key"),
        model = prefs.getString("${preset.id}.model", null),
        contextWindow = prefs.getInt("${preset.id}.context", DEFAULT_CONTEXT),
    )

    fun save(config: ProviderConfig) {
        val p = config.preset.id
        prefs.edit {
            putString("active", p)
            putString("$p.baseUrl", config.baseUrl.trim().trimEnd('/'))
            putString("$p.model", config.model)
            putInt("$p.context", config.contextWindow)
        }
        secrets.put("$p.key", config.apiKey)
        _config.value = load()
    }

    private fun load(): ProviderConfig = configFor(ProviderPreset.byId(prefs.getString("active", null)) ?: ProviderPreset.GROQ)

    companion object {
        /** Conservative guess when the provider doesn't report a context size. */
        const val DEFAULT_CONTEXT = 8192
    }
}
