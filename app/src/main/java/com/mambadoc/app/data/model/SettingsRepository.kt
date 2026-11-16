package com.mambadoc.app.data.model

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("mambadoc_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settingsFlow: StateFlow<AppSettings> = _settings.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        save(next)
        _settings.value = next
    }

    private fun load(): AppSettings {
        return AppSettings(
            nCtx = prefs.getInt("n_ctx", 16384),
            nThreads = prefs.getInt("n_threads", 6),
            temperature = prefs.getFloat("temperature", 0.7f),
            topP = prefs.getFloat("top_p", 0.9f),
            minP = prefs.getFloat("min_p", 0.05f),
            ragTopK = prefs.getInt("rag_top_k", 6),
            thinkingMode = prefs.getBoolean("thinking_mode", false),
            lowRamSwap = prefs.getBoolean("low_ram_swap", false),
            flagSecure = prefs.getBoolean("flag_secure", true),
            forceDark = prefs.getBoolean("force_dark", true),
            llmModelPath = prefs.getString("llm_model_path", "") ?: "",
            embedModelPath = prefs.getString("embed_model_path", "") ?: "",
            maxGenTokens = prefs.getInt("max_gen_tokens", 1024)
        )
    }

    private fun save(s: AppSettings) {
        prefs.edit()
            .putInt("n_ctx", s.nCtx)
            .putInt("n_threads", s.nThreads)
            .putFloat("temperature", s.temperature)
            .putFloat("top_p", s.topP)
            .putFloat("min_p", s.minP)
            .putInt("rag_top_k", s.ragTopK)
            .putBoolean("thinking_mode", s.thinkingMode)
            .putBoolean("low_ram_swap", s.lowRamSwap)
            .putBoolean("flag_secure", s.flagSecure)
            .putBoolean("force_dark", s.forceDark)
            .putString("llm_model_path", s.llmModelPath)
            .putString("embed_model_path", s.embedModelPath)
            .putInt("max_gen_tokens", s.maxGenTokens)
            .apply()
    }

    companion object {
        val DEFAULT = AppSettings()
    }
}
