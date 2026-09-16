cmake_minimum_required(VERSION 3.18.1)
project("splendor_native")
project("vision_preprocessor")

add_library(splendor_native SHARED native_input.cpp)
add_library(vision_preprocessor SHARED vision_preprocessor.cpp)

target_compile_options(splendor_native PRIVATE -ftls-model=global-dynamic)
target_compile_options(vision_preprocessor PRIVATE -ftls-model=global-dynamic)

find_library(log-lib log)

target_link_libraries(splendor_native ${log-lib})
target_link_libraries(vision_preprocessor ${log-lib})
