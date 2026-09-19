cmake_minimum_required(VERSION 3.18.1)
project("splendor_native" C CXX)

add_library(splendor_native SHARED native_input.cpp native_kicking_posture.c native_agility_physics.c native_registration.c)

find_library(log-lib log)
target_link_libraries(splendor_native ${log-lib})
