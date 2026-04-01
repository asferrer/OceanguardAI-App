cmake_minimum_required(VERSION 3.22.1)
project(oceanguard_llama)

# ARM64 with dotprod + fp16 (Exynos 2200 = ARMv8.4-A)
# NOTE: -fno-finite-math-only required: ggml vec.h checks for it at compile time
set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS}   -O3 -march=armv8-a+dotprod+fp16 -fno-finite-math-only")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -march=armv8-a+dotprod+fp16 -fno-finite-math-only -std=c++17")

# CPU-only — no GPU backends (Xclipse 920 drivers are buggy; EDEN NPU SDK is private)
set(GGML_METAL    OFF CACHE BOOL "" FORCE)
set(GGML_CUDA     OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN   OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL   OFF CACHE BOOL "" FORCE)
set(LLAMA_NATIVE  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)

# Fetch llama.cpp — shallow clone of exact tag (faster download, no git-describe needed).
# b8583 = March 30 2026; first version with qwen35 arch support (added ~Feb 19 2026).
# Architecture "qwen35" in GGUF requires llama.cpp >= ~b7900.
include(FetchContent)
FetchContent_Declare(llama_cpp
    GIT_REPOSITORY https://github.com/ggerganov/llama.cpp.git
    GIT_TAG        b8583
    GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(llama_cpp)

# ---- Vision bridge: stub mode (mtmd API migration pending) ----
# llama.cpp b8583 moved vision to tools/mtmd/ with a new API that pulls in
# ~20 model backends + audio deps (conformer, whisper-enc, miniaudio).
# OceanGuard uses Qwen3.5-2B text-only for reports — vision is out of scope.
# Set LLAVA_AVAILABLE=FALSE so llava_bridge.cpp compiles as no-ops.
set(LLAVA_SOURCES "")
set(LLAVA_AVAILABLE FALSE)
message(STATUS "Vision bridge: stub mode (text-only reports, mtmd migration deferred)")

add_library(llama_oceanguard SHARED
    llama_bridge.cpp
    llava_bridge.cpp
    ${LLAVA_SOURCES}
)

target_compile_features(llama_oceanguard PRIVATE cxx_std_17)

if(LLAVA_AVAILABLE)
    target_compile_definitions(llama_oceanguard PRIVATE LLAVA_AVAILABLE=1)
    target_include_directories(llama_oceanguard
        PRIVATE
        ${llama_cpp_SOURCE_DIR}/tools/mtmd
        ${llama_cpp_SOURCE_DIR}/vendor       # miniaudio/miniaudio.h (needed by mtmd-helper)
    )
endif()

target_link_libraries(llama_oceanguard
    PRIVATE
    llama
    ggml
    log
    android
    jnigraphics
)

target_include_directories(llama_oceanguard
    PRIVATE
    ${llama_cpp_SOURCE_DIR}/include
    ${llama_cpp_SOURCE_DIR}/src
    ${llama_cpp_SOURCE_DIR}/ggml/include
    ${llama_cpp_SOURCE_DIR}/ggml/src
)
