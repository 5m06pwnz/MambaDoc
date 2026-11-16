cmake_minimum_required(VERSION 3.22.1)
project(mambadoc_jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

# ---------------------------------------------------------------------------
# Target: Samsung Galaxy S26 Ultra / Snapdragon 8 Elite Gen 5 (Oryon V3)
# Only arm64-v8a is supported. Portable baseline + runtime KleidiAI dispatch.
# ---------------------------------------------------------------------------
if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "MambaDoc supports arm64-v8a only (got ${ANDROID_ABI})")
endif()

# Prefer a local submodule so CI and local builds are deterministic.
# Fallback to FetchContent only when the submodule is absent.
set(LLAMA_CPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/llama.cpp")

if(EXISTS "${LLAMA_CPP_DIR}/CMakeLists.txt")
    message(STATUS "Using local llama.cpp submodule at ${LLAMA_CPP_DIR}")
    # Force the options before add_subdirectory so they stick.
    set(GGML_CPU_KLEIDIAI OFF  CACHE BOOL "KleidiAI for Oryon / Snapdragon" FORCE)
    set(GGML_NATIVE       OFF CACHE BOOL "Portable arm64-v8a" FORCE)
    set(GGML_OPENMP       OFF CACHE BOOL "" FORCE)
    set(GGML_LLAMAFILE    OFF CACHE BOOL "" FORCE)
    set(LLAMA_OPENSSL     OFF CACHE BOOL "" FORCE)
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    set(GGML_BACKEND_DL   OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER  OFF CACHE BOOL "" FORCE)
    add_subdirectory(${LLAMA_CPP_DIR} llama_build EXCLUDE_FROM_ALL)
else()
    message(STATUS "No local llama.cpp – fetching via FetchContent")
    include(FetchContent)
    # IMPORTANT: replace with a verified post-v1.0.0 SHA that has
    # nemotron_h + granitemoehybrid + stable KleidiAI on Android.
    # After first successful device build, pin the exact commit hash here.
    FetchContent_Declare(
        llama
        GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
        GIT_TAG        master
        GIT_SHALLOW    TRUE
    )
    set(GGML_CPU_KLEIDIAI OFF  CACHE BOOL "" FORCE)
    set(GGML_NATIVE       OFF CACHE BOOL "" FORCE)
    set(GGML_OPENMP       OFF CACHE BOOL "" FORCE)
    set(GGML_LLAMAFILE    OFF CACHE BOOL "" FORCE)
    set(LLAMA_OPENSSL     OFF CACHE BOOL "" FORCE)
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    set(GGML_BACKEND_DL   OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER  OFF CACHE BOOL "" FORCE)
    FetchContent_MakeAvailable(llama)
    set(LLAMA_CPP_DIR ${llama_SOURCE_DIR})
endif()

# Our JNI shared library
add_library(mambadoc_jni SHARED
    jni_bridge.cpp
)

target_include_directories(mambadoc_jni PRIVATE
    ${LLAMA_CPP_DIR}/include
    ${LLAMA_CPP_DIR}/ggml/include
)

# Link static llama + ggml. Order matters for some NDK linkers.
target_link_libraries(mambadoc_jni
    PRIVATE
        llama
        ggml
        android
        log
        atomic
)

# Portable but aggressive optimisations for arm64-v8a.
# Do NOT set -march=armv8.7a globally – KleidiAI selects kernels at runtime.
target_compile_options(mambadoc_jni PRIVATE
    $<$<COMPILE_LANGUAGE:CXX>:-std=c++17>
    -O3
    -fno-exceptions
    -fno-rtti
    -ffunction-sections
    -fdata-sections
    -fvisibility=hidden
    
    
    -Wno-unused-parameter
)

target_link_options(mambadoc_jni PRIVATE
    -Wl,--gc-sections
    -Wl,-z,max-page-size=16384
    -Wl,--exclude-libs,ALL
)

set_target_properties(mambadoc_jni PROPERTIES
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON
    POSITION_INDEPENDENT_CODE ON
)
