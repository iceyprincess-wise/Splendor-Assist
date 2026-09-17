cmake_minimum_required(VERSION 3.18.1)
project("splendor_native")

add_library(splendor_native SHARED native_input.cpp)
add_library(vision_preprocessor SHARED vision_preprocessor.cpp)

# Disable GWP-ASan to prevent R_AARCH64_TLS_TPREL64 IE TLS relocation failure on dlopen
target_compile_options(splendor_native PRIVATE -fno-sanitize=gwp-asan)
target_compile_options(vision_preprocessor PRIVATE -fno-sanitize=gwp-asan)

find_library(log-lib log)

target_link_libraries(splendor_native ${log-lib})
target_link_libraries(vision_preprocessor ${log-lib})
