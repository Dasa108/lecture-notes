cmake_minimum_required(VERSION 3.22.1)
project(lecturenotes_native C CXX)

set(CMAKE_CXX_STANDARD 17)
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp)

# Build ggml's CPU backend once per instruction-set level (armv8.0, armv8.2+dotprod,
# +fp16, armv8.6 i8mm, armv9 ...) and let ggml pick the best one for the phone at runtime.
set(BUILD_SHARED_LIBS      ON  CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL        ON  CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS  ON  CACHE BOOL "" FORCE)
set(GGML_NATIVE            OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL           OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2           OFF CACHE BOOL "" FORCE)

add_subdirectory(${WHISPER_DIR} whisper.cpp)

add_library(whisper_jni SHARED whisper_jni.cpp)
target_link_libraries(whisper_jni PRIVATE whisper ggml ggml-base log android)
target_compile_options(whisper_jni PRIVATE -O3 -Wall)
