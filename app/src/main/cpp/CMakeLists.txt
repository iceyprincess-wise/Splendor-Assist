cmake_minimum_required(VERSION 3.18.1)
project("splendor_native" C CXX)

add_library(splendor_native SHARED native_input.cpp)
add_library(vision_core SHARED vision_core.c)

find_library(log-lib log)

target_link_libraries(splendor_native ${log-lib})
target_link_libraries(vision_core ${log-lib})
