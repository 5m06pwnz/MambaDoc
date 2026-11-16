package com.mambadoc.app.data.model

data class AppSettings(
    val nCtx: Int = 16384,
    val nThreads: Int = 6,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val minP: Float = 0.05f,
    val ragTopK: Int = 6,
    val thinkingMode: Boolean = false,          // Deep Reasoning vs Fast Answer
    val lowRamSwap: Boolean = false,            // unload embedder before LLM on low RAM
    val flagSecure: Boolean = true,             // prevent screenshots by default
    val forceDark: Boolean = true,
    val llmModelPath: String = "",
    val embedModelPath: String = "",
    val maxGenTokens: Int = 1024
)
