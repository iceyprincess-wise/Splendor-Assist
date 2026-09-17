cmake_minimum_required(VERSION 3.18.1)
project("splendor_native" C CXX)

# MERGED: Single shared library prevents Bionic static TLS slot exhaustion on dlopen
add_library(splendor_native SHARED native_input.cpp vision_core.c)

find_library(log-lib log)
target_link_libraries(splendor_native ${log-lib})
