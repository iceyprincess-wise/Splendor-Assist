cmake_minimum_required(VERSION 3.22.1)
project("vision_preprocessor")

add_library(vision_preprocessor SHARED
    vision_preprocessor.cpp)

find_library(log-lib log)

target_link_libraries(vision_preprocessor
    ${log-lib})
