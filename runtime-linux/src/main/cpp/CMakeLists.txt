cmake_minimum_required(VERSION 3.22.1)
project(forgepty C CXX)

# A deliberately small library: it exists only to give the terminal a real
# pseudo-terminal, because a pipe cannot carry window size, job control or
# ncurses. Everything else about the runtime is Kotlin.
add_library(forgepty SHARED forge_pty.cpp)

find_library(log-lib log)

target_compile_options(forgepty PRIVATE
    -Wall
    -Wextra
    -Werror
    -fno-exceptions
    -fno-rtti
    -fvisibility=hidden)

target_link_libraries(forgepty ${log-lib})

# armeabi-v7a is the reason this project exists. Building it must not be
# silently dropped by a toolchain default.
message(STATUS "forgepty: building for ABI ${ANDROID_ABI}, API ${ANDROID_PLATFORM}")
