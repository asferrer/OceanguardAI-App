cmake_minimum_required(VERSION 3.22.1)
project(oceanguard_llama)

# ARM64 optimized for Exynos 2200 (Cortex-X2 = ARMv9 ⊃ ARMv8.6-A)
# - dotprod: accelerates INT8 dot products in ggml quantized kernels
# - fp16: half-precision compute for flash attention KV cache
# - i8mm: INT8 matrix multiply (ARMv8.6+), ~20-30% faster Q4_K_M matmul
# - fno-finite-math-only: required by ggml vec.h compile-time checks
set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS}   -O3 -march=armv8.6-a+dotprod+fp16+i8mm -fno-finite-math-only")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -march=armv8.6-a+dotprod+fp16+i8mm -fno-finite-math-only -std=c++17")

# GPU backends — Vulkan requires host Vulkan SDK (glslc) for shader compilation.
# Enable GGML_VULKAN ON after installing Vulkan SDK (https://vulkan.lunarg.com/).
# Falls back to CPU at runtime if Vulkan init fails (e.g. Xclipse 920 driver issues).
set(GGML_METAL    OFF CACHE BOOL "" FORCE)
set(GGML_CUDA     OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN   OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL   OFF CACHE BOOL "" FORCE)
set(LLAMA_NATIVE  OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)

# Fetch llama.cpp — shallow clone of exact tag (faster download, no git-describe needed).
# b8678 = April 6 2026; supports qwen35 (>= ~b7900) and gemma4 (>= b8637).
# Includes critical gemma4 fixes: tokenizer (b8671), softcapping (b8662), byte tokens (b8678).
include(FetchContent)
FetchContent_Declare(llama_cpp
    GIT_REPOSITORY https://github.com/ggerganov/llama.cpp.git
    GIT_TAG        b8678
    GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(llama_cpp)

# ---- Vision bridge: enabled for Gemma 4 + Qwen multimodal support ----
# llama.cpp mtmd API provides multi-modal support (image + text).
# Enabled to support Gemma 4 E2B vision (mmproj) and Qwen3.5 vision verification.
# Set LLAVA_AVAILABLE=TRUE to compile llava_bridge.cpp with full mtmd support.
set(LLAVA_SOURCES "")
set(LLAVA_AVAILABLE TRUE)
message(STATUS "Vision bridge: enabled (Gemma 4 + Qwen multimodal via mtmd)")

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
