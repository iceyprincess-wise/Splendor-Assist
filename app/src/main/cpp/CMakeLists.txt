cmake_minimum_required(VERSION 3.18.1)
project("splendor_native" C CXX)

add_library(splendor_native SHARED native_input.cpp native_kicking_posture.c native_agility_physics.c native_registration.c)

find_library(log-lib log)
target_link_libraries(splendor_native PRIVATE ${log-lib}

# SPLENDOR_NATIVE_LINK_SHIM_V6_BEGIN
set(SPLENDOR_SHIM_DIR "${CMAKE_CURRENT_SOURCE_DIR}/native-shims/bin")
get_filename_component(SPLENDOR_SHIM_DIR "${SPLENDOR_SHIM_DIR}" ABSOLUTE)

if(NOT EXISTS "${SPLENDOR_SHIM_DIR}/ld.lld")
  message(FATAL_ERROR "SPLENDOR_LINK_SHIM_V6: missing ${SPLENDOR_SHIM_DIR}/ld.lld")
endif()

if(NOT EXISTS "${SPLENDOR_SHIM_DIR}/ld")
  message(FATAL_ERROR "SPLENDOR_LINK_SHIM_V6: missing ${SPLENDOR_SHIM_DIR}/ld")
endif()

set(CMAKE_C_COMPILER_LINKER "${SPLENDOR_SHIM_DIR}/ld.lld" CACHE FILEPATH "Splendor native linker shim" FORCE)
set(CMAKE_CXX_COMPILER_LINKER "${SPLENDOR_SHIM_DIR}/ld.lld" CACHE FILEPATH "Splendor native linker shim" FORCE)

set(SPLENDOR_ANDROID_API_LEVEL "26")

if(DEFINED CMAKE_SYSTEM_VERSION)
  if(CMAKE_SYSTEM_VERSION MATCHES "^[0-9]+$")
    set(SPLENDOR_ANDROID_API_LEVEL "${CMAKE_SYSTEM_VERSION}")
  endif()
endif()

set(SPLENDOR_SYSROOT "${CMAKE_SYSROOT}")
if(SPLENDOR_SYSROOT)
  get_filename_component(SPLENDOR_SYSROOT "${SPLENDOR_SYSROOT}" ABSOLUTE)
endif()

if(NOT SPLENDOR_SYSROOT OR NOT IS_DIRECTORY "${SPLENDOR_SYSROOT}")
  set(SPLENDOR_NDK "")

  if(DEFINED CMAKE_ANDROID_NDK)
    get_filename_component(SPLENDOR_NDK "${CMAKE_ANDROID_NDK}" ABSOLUTE)
  elseif(DEFINED ANDROID_NDK)
    get_filename_component(SPLENDOR_NDK "${ANDROID_NDK}" ABSOLUTE)
  endif()

  if(SPLENDOR_NDK)
    file(GLOB SPLENDOR_SYSROOT_GLOB
      "${SPLENDOR_NDK}/toolchains/llvm/prebuilt/*/sysroot"
      "${SPLENDOR_NDK}/toolchains/llvm/prebuilt/sysroot"
    )
    list(LENGTH SPLENDOR_SYSROOT_GLOB SPLENDOR_SYSROOT_COUNT)

    if(SPLENDOR_SYSROOT_COUNT GREATER 0)
      list(GET SPLENDOR_SYSROOT_GLOB 0 SPLENDOR_SYSROOT)
      get_filename_component(SPLENDOR_SYSROOT "${SPLENDOR_SYSROOT}" ABSOLUTE)
    endif()
  endif()
endif()

if(NOT SPLENDOR_SYSROOT OR NOT IS_DIRECTORY "${SPLENDOR_SYSROOT}")
  message(FATAL_ERROR "SPLENDOR_LINK_SHIM_V6: sysroot not resolved. CMAKE_SYSROOT='${CMAKE_SYSROOT}' CMAKE_ANDROID_NDK='${CMAKE_ANDROID_NDK}' ANDROID_NDK='${ANDROID_NDK}'")
endif()

set(SPLENDOR_ARCH_DIR "${SPLENDOR_SYSROOT}/usr/lib/aarch64-linux-android")
set(SPLENDOR_LIBDIR "")

foreach(_dir "${SPLENDOR_ARCH_DIR}/${SPLENDOR_ANDROID_API_LEVEL}" "${SPLENDOR_ARCH_DIR}" "${SPLENDOR_SYSROOT}/usr/lib")
  if(EXISTS "${_dir}/libc.so")
    set(SPLENDOR_LIBDIR "${_dir}")
    break()
  endif()
endforeach()

if(NOT SPLENDOR_LIBDIR)
  message(FATAL_ERROR "SPLENDOR_LINK_SHIM_V6: libc.so not found under '${SPLENDOR_ARCH_DIR}'")
endif()

set(SPLENDOR_DYNAMIC_LIBS "")

foreach(_lib libc libm libdl)
  set(_found FALSE)

  foreach(_dir "${SPLENDOR_LIBDIR}" "${SPLENDOR_ARCH_DIR}" "${SPLENDOR_SYSROOT}/usr/lib")
    if(EXISTS "${_dir}/${_lib}.so")
      list(APPEND SPLENDOR_DYNAMIC_LIBS "${_dir}/${_lib}.so")
      set(_found TRUE)
      break()
    endif()
  endforeach()

  if(NOT _found)
    message(FATAL_ERROR "SPLENDOR_LINK_SHIM_V6: missing ${_lib}.so under '${SPLENDOR_SYSROOT}/usr/lib'")
  endif()
endforeach()

set(_cxx "")
foreach(_dir "${SPLENDOR_ARCH_DIR}/${SPLENDOR_ANDROID_API_LEVEL}" "${SPLENDOR_ARCH_DIR}" "${SPLENDOR_SYSROOT}/usr/lib")
  if(EXISTS "${_dir}/libc++_shared.so")
    set(_cxx "${_dir}/libc++_shared.so")
    break()
  endif()
endforeach()

if(_cxx)
  list(APPEND SPLENDOR_DYNAMIC_LIBS "${_cxx}")
endif()

target_link_options(splendor_native PRIVATE
  "-B${SPLENDOR_SHIM_DIR}"
  "-Wl,-Bdynamic"
  "-Wl,--no-as-needed"
)

target_link_libraries(splendor_native PRIVATE
  ${SPLENDOR_DYNAMIC_LIBS}
)
# SPLENDOR_NATIVE_LINK_SHIM_V6_END
