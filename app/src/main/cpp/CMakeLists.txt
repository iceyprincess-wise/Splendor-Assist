cmake_minimum_required(VERSION 3.18.1)
project("splendor_native" C CXX)

add_library(splendor_native SHARED
  native_input.cpp
  native_kicking_posture.c
  native_agility_physics.c
  native_registration.c
  native_arbitration.c
  native_gameplay_compute.c
  native_vision_blob.c
  native_vision_preprocessor.c
)

find_library(log-lib log)

set_target_properties(splendor_native PROPERTIES POSITION_INDEPENDENT_CODE ON)

target_compile_options(splendor_native PRIVATE
  -fPIC
  -ftls-model=global-dynamic
)

# SPLENDOR_NATIVE_LINK_SHIM_V9_BEGIN
set(SPLENDOR_SHIM_DIR "${CMAKE_CURRENT_SOURCE_DIR}/native-shims/bin")
get_filename_component(SPLENDOR_SHIM_DIR "${SPLENDOR_SHIM_DIR}" ABSOLUTE)

if(NOT EXISTS "${SPLENDOR_SHIM_DIR}/ld.lld")
  message(FATAL_ERROR "SPLENDOR_LINK_SHIM_V9: missing ${SPLENDOR_SHIM_DIR}/ld.lld")
endif()

if(NOT EXISTS "${SPLENDOR_SHIM_DIR}/ld")
  message(FATAL_ERROR "SPLENDOR_LINK_SHIM_V9: missing ${SPLENDOR_SHIM_DIR}/ld")
endif()

target_link_options(splendor_native PRIVATE
  "-B${SPLENDOR_SHIM_DIR}"
  "-Wl,-Bdynamic"
  "-Wl,--no-as-needed"
)

target_link_libraries(splendor_native PRIVATE
  ${log-lib}
)
# SPLENDOR_NATIVE_LINK_SHIM_V9_END

# SPLENDOR_V31A_SUPPRESS_UNUSED_LINK_PATH_WARNING_BEGIN
target_compile_options(splendor_native PRIVATE -Wno-unused-command-line-argument)
# SPLENDOR_V31A_SUPPRESS_UNUSED_LINK_PATH_WARNING_END
