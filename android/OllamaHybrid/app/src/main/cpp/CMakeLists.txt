cmake_minimum_required(VERSION 3.22.1)
project(nexus_llama_jni LANGUAGES C CXX)

include(FetchContent)

# Inference speed must not depend on the APK flavour: if a Debug configuration
# reaches this file anyway, the engine is still compiled with optimisation.
if (NOT CMAKE_BUILD_TYPE MATCHES "Rel")
    message(STATUS "NEXUS: ${CMAKE_BUILD_TYPE} build, forcing -O3 for llama.cpp")
    add_compile_options(-O3 -DNDEBUG)
endif()

# CI memory-safety job only (`gradle -Pnexus.asan=true`): every native library of
# the engine, llama.cpp and ggml included, is built with AddressSanitizer, so a
# use-after-free or an overflow in the load / answer / cancel / free cycle crashes
# the instrumented test with a report instead of passing silently.
option(NEXUS_ASAN "Build the engine with AddressSanitizer (CI lifecycle job)" OFF)
if (NEXUS_ASAN)
    message(STATUS "NEXUS: AddressSanitizer build")
    add_compile_options(-fsanitize=address -fno-omit-frame-pointer)
    add_link_options(-fsanitize=address)
    add_compile_definitions(NEXUS_ASAN=1)
endif()

# ggml's CPU backend is a separate library loaded at runtime from the app's
# native library folder, in several variants; the best one for this CPU is
# picked by feature detection — the approach of llama.cpp's own Android example.
# Phones (arm64): ARMv8.0, v8.2 dotprod, v8.2 fp16, v8.6 i8mm, v9 SVE2/SME.
# The x86_64 copy exists only for CI emulators (SSE4.2 … AVX-512 variants), so
# CI exercises the same runtime selection that phones rely on.
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
if (NEXUS_ASAN)
    # one baseline CPU library keeps the sanitizer build short; the runtime
    # loading path (ggml_backend_load_all_from_path) is the same
    set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
else()
    set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
endif()
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
# KleidiAI kernels are Arm-only; the x86_64 build exists just for CI emulators.
if (ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
else()
    set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
endif()

FetchContent_Declare(
    llama_cpp
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG v1.0.0
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(llama_cpp)

add_library(nexus_llama_jni SHARED LlamaCppJni.cpp)
target_compile_features(nexus_llama_jni PRIVATE cxx_std_17)
target_include_directories(nexus_llama_jni PRIVATE ${llama_cpp_SOURCE_DIR}/include)
target_include_directories(nexus_llama_jni PRIVATE ${llama_cpp_SOURCE_DIR}/ggml/include)
target_link_libraries(nexus_llama_jni PRIVATE llama ggml android log)
