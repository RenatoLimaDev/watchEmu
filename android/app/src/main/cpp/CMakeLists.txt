cmake_minimum_required(VERSION 3.22.1)
project("watchemu")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(watchemu SHARED jni_bridge.cpp)

target_compile_options(watchemu PRIVATE
    -O3
    -ffast-math
    -funroll-loops
    -fvisibility=hidden)

find_library(log-lib log)
target_link_libraries(watchemu ${log-lib})
