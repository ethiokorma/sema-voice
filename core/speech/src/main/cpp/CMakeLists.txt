cmake_minimum_required(VERSION 3.22)
project(sema_whisper C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# whisper.cpp (MIT) is fetched by scripts/fetch-whisper-cpp.{sh,ps1}; it is not committed.
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)
if (NOT EXISTS ${WHISPER_DIR}/include/whisper.h)
    message(FATAL_ERROR "whisper.cpp sources missing. Run scripts/fetch-whisper-cpp.sh (or .ps1) first.")
endif()

# ---- ggml configuration -----------------------------------------------------------
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)      # ggml's own thread pool; avoids libomp packaging
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)

if (${ANDROID_ABI} STREQUAL "arm64-v8a")
    # ARMv8.2 with half-precision + dot product: every mainstream SoC since 2018 (Cortex-A55/A75+).
    # Older 2017 chips (SD835, Kirin 970) lack these; the Kotlin loader checks /proc/cpuinfo and
    # refuses to load this library instead of crashing (see WhisperNative.deviceSupportsNativeLib).
    set(GGML_CPU_ARM_ARCH "armv8.2-a+fp16+dotprod" CACHE STRING "" FORCE)
endif()

add_subdirectory(${WHISPER_DIR}/ggml ggml EXCLUDE_FROM_ALL)

# ---- whisper + JNI bridge ----------------------------------------------------------
add_library(sema_whisper SHARED
    ${WHISPER_DIR}/src/whisper.cpp
    ${WHISPER_DIR}/src/aneforge/whisper-aneforge.cpp
    sema_whisper_jni.cpp
)

target_include_directories(sema_whisper PRIVATE
    ${WHISPER_DIR}
    ${WHISPER_DIR}/include
    ${WHISPER_DIR}/src
    ${WHISPER_DIR}/ggml/include
    ${WHISPER_DIR}/ggml/src
    ${WHISPER_DIR}/ggml/src/ggml-cpu
)

target_compile_definitions(sema_whisper PRIVATE GGML_USE_CPU WHISPER_VERSION="1.9.5")
target_compile_options(sema_whisper PRIVATE -O3 -fvisibility=hidden -fvisibility-inlines-hidden -ffunction-sections -fdata-sections)
target_link_options(sema_whisper PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
    # 16 KB page-size compliance (Google Play requirement for targetSdk 35+). NDK r28+ does
    # this by default; r27 needs the explicit flags.
    -Wl,-z,max-page-size=16384
    -Wl,-z,common-page-size=16384
)

find_library(log-lib log)
target_link_libraries(sema_whisper PRIVATE ggml ${log-lib} android)
