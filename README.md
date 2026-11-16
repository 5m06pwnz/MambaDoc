# MambaDoc — Air-Gapped Document Intelligence & RAG

**100 % offline** Android document Q&A assistant built for the Samsung Galaxy S26 Ultra (Snapdragon 8 Elite Gen 5 / Oryon V3, 12 GB RAM, Android 16 / API 36).

## Design goals

- **No `INTERNET` permission** — air-gapped by construction.
- Primary LLM: `NVIDIA-Nemotron-3-Nano-4B-Q4_K_M.gguf` (Mamba-2 hybrid / `nemotron_h`) and compatible `granite-4.0-h-*` GGUFs.
- Embeddings: `nomic-embed-text-v1.5-Q8_0.gguf` with mandatory `search_document:` / `search_query:` prefixes + L2 normalisation.
- Pure-Kotlin document parsers (DOCX via Zip + XmlPullParser, PDF via `PdfRenderer.getTextContents()` on API 35+ with lightweight FlateDecode fallback).
- Hybrid retrieval: cosine vector search + SQLite FTS5 BM25 fused by Reciprocal Rank Fusion.
- Native `llama.cpp` (modern post-1.0 C API) with KleidiAI, `n_seq_max = 1`, `llama_memory_clear` for clean Mamba-2 state between turns.
- Optional `FLAG_SECURE` (on by default) and Low-RAM Swap Mode.

## Project layout

```
MambaDoc/
├── app/
│   ├── build.gradle.kts
│   ├── src/main/
│   │   ├── AndroidManifest.xml          # no INTERNET
│   │   ├── cpp/
│   │   │   ├── CMakeLists.txt           # KleidiAI, arm64-v8a only
│   │   │   ├── jni_bridge.cpp           # modern llama.h API
│   │   │   └── third_party/llama.cpp/   # git submodule or CI fetch
│   │   └── java/com/mambadoc/app/
│   │       ├── native/LlamaNative.kt
│   │       ├── data/{db,parser,rag,model}/
│   │       └── ui/
│   └── …
├── .github/workflows/android-build.yml
└── README.md
```

## Building

### Prerequisites

- JDK 17
- Android SDK Platform 36, Build-Tools 36, NDK r28, CMake 3.22+
- A physical arm64-v8a device (or emulator with sufficient RAM)

### Local

```bash
git clone --recursive <this-repo>
# or let the CMake FetchContent / CI script pull llama.cpp
./gradlew assembleDebug
```

The first native build will compile `llama.cpp` static libs with:

- `-DGGML_CPU_KLEIDIAI=ON`
- `-DGGML_NATIVE=OFF`
- `-DGGML_OPENMP=OFF`
- arm64-v8a only

### GitHub Actions

Push to `main`/`master`. The workflow:

1. Sets up JDK 17 + NDK r28 + CMake
2. Ensures `llama.cpp` is present (submodule or shallow clone)
3. Runs `./gradlew assembleDebug`
4. Uploads `app-debug.apk` as an artifact

## Model placement

1. Open **Models & Settings**
2. Import the LLM `.gguf` (Nemotron-3-Nano-4B or Granite-4.0-H)
3. Import `nomic-embed-text-v1.5-Q8_0.gguf`
4. Files are copied into app-private `filesDir/models/`

Alternatively:

```bash
adb push NVIDIA-Nemotron-3-Nano-4B-Q4_K_M.gguf /sdcard/Documents/Models/
# then pick via Storage Access Framework
```

## Security notes

- `android.permission.INTERNET` is **not** declared.
- `network_security_config` blocks cleartext and is present for defence-in-depth.
- Backup / data-extraction rules exclude all app data.
- `FLAG_SECURE` is enabled by default (toggle in Settings).
- No telemetry, no analytics, no remote calls.

## Known integration points still requiring device validation

1. Exact `llama.cpp` commit/tag that has stable `nemotron_h` + `granitemoehybrid` + KleidiAI on Android (pin after first successful device run).
2. Nemotron-3 thinking-mode chat template tags (currently a simple `<think>` wrapper — adjust to the official template once confirmed).
3. Thermal / thread affinity tuning on Oryon V3 (default 6 decode / 8 prompt threads).
4. Exhaustive PDF edge-case coverage for the pure-Kotlin FlateDecode fallback.

## License

MIT (application code).  
`llama.cpp` remains under its own MIT license.
