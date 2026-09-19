cmake_minimum_required(VERSION 3.18.1)
project("splendor_native" C CXX)

add_library(splendor_native SHARED native_input.cpp native_kicking_posture.c native_agility_physics.c native_registration.c)

find_library(log-lib log)
target_link_libraries(splendor_native ${log-lib})

# SPLENDOR_NATIVE_TLS_FIX_BEGIN
set_target_properties(splendor_native PROPERTIES POSITION_INDEPENDENT_CODE ON)

target_compile_options(splendor_native PRIVATE
  -fPIC
  -ftls-model=global-dynamic
)

set(_splendor_san_flags
  "-fsanitize=gwp-asan"
  "-fsanitize=address"
  "-fsanitize=hwaddress"
  "-fsanitize=thread"
  "-fsanitize=undefined"
)

set(_splendor_vars
  CMAKE_C_FLAGS
  CMAKE_C_FLAGS_DEBUG
  CMAKE_C_FLAGS_RELEASE
  CMAKE_C_FLAGS_RELWITHDEBINFO
  CMAKE_C_FLAGS_MINSIZEREL
  CMAKE_CXX_FLAGS
  CMAKE_CXX_FLAGS_DEBUG
  CMAKE_CXX_FLAGS_RELEASE
  CMAKE_CXX_FLAGS_RELWITHDEBINFO
  CMAKE_CXX_FLAGS_MINSIZEREL
  CMAKE_SHARED_LINKER_FLAGS
  CMAKE_SHARED_LINKER_FLAGS_DEBUG
  CMAKE_SHARED_LINKER_FLAGS_RELEASE
  CMAKE_SHARED_LINKER_FLAGS_RELWITHDEBINFO
  CMAKE_SHARED_LINKER_FLAGS_MINSIZEREL
)

foreach(_var IN LISTS _splendor_vars)
  if(DEFINED ${_var})
    set(_val "${${_var}}")
    foreach(_flag IN LISTS _splendor_san_flags)
      string(REPLACE "${_flag}" "" _val "${_val}")
    endforeach()
    string(REGEX REPLACE "  +" " " _val "${_val}")
    string(STRIP "${_val}" _val)
    set(${_var} "${_val}")
  endif()
endforeach()
# SPLENDOR_NATIVE_TLS_FIX_END
