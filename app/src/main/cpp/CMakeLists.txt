cmake_minimum_required(VERSION 3.18.1)
project("splendor_native" C CXX)

add_library(splendor_native SHARED native_input.cpp native_kicking_posture.c native_agility_physics.c native_registration.c)

find_library(log-lib log)
target_link_libraries(splendor_native ${log-lib})

# SPLENDOR_NATIVE_DYNAMIC_LIBC_BEGIN
set(SPLENDOR_ANDROID_API_LEVEL "26")

if(DEFINED CMAKE_SYSTEM_VERSION AND NOT CMAKE_SYSTEM_VERSION STREQUAL "")
  set(SPLENDOR_ANDROID_API_LEVEL "${CMAKE_SYSTEM_VERSION}")
endif()

if(DEFINED ANDROID_PLATFORM)
  string(REGEX REPLACE "^android-([0-9]+).*$" "\1" SPLENDOR_PLATFORM_API "${ANDROID_PLATFORM}")
  if(SPLENDOR_PLATFORM_API MATCHES "^[0-9]+$")
    set(SPLENDOR_ANDROID_API_LEVEL "${SPLENDOR_PLATFORM_API}")
  endif()
endif()

set(SPLENDOR_SYSROOT "${CMAKE_SYSROOT}")

if(NOT SPLENDOR_SYSROOT)
  if(DEFINED CMAKE_ANDROID_NDK)
    get_filename_component(SPLENDOR_NDK "${CMAKE_ANDROID_NDK}" ABSOLUTE)
    file(TO_CMAKE_PATH "${SPLENDOR_NDK}/toolchains/llvm/prebuilt/linux-x86_64/sysroot" SPLENDOR_SYSROOT)
  elseif(DEFINED ANDROID_NDK)
    get_filename_component(SPLENDOR_NDK "${ANDROID_NDK}" ABSOLUTE)
    file(TO_CMAKE_PATH "${SPLENDOR_NDK}/toolchains/llvm/prebuilt/linux-x86_64/sysroot" SPLENDOR_SYSROOT)
  endif()
endif()

if(NOT SPLENDOR_SYSROOT OR NOT IS_DIRECTORY "${SPLENDOR_SYSROOT}")
  message(FATAL_ERROR "SPLENDOR_DYNAMIC_LIBC: sysroot not resolved. CMAKE_SYSROOT='${CMAKE_SYSROOT}'")
endif()

set(SPLENDOR_ARCH_DIR "${SPLENDOR_SYSROOT}/usr/lib/aarch64-linux-android")
set(SPLENDOR_LIBDIR_API "${SPLENDOR_ARCH_DIR}/${SPLENDOR_ANDROID_API_LEVEL}")
set(SPLENDOR_LIBDIR "")

foreach(_dir "${SPLENDOR_LIBDIR_API}" "${SPLENDOR_ARCH_DIR}")
  if(EXISTS "${_dir}/libc.so")
    set(SPLENDOR_LIBDIR "${_dir}")
    break()
  endif()
endforeach()

if(NOT SPLENDOR_LIBDIR)
  message(FATAL_ERROR "SPLENDOR_DYNAMIC_LIBC: libc.so not found under '${SPLENDOR_ARCH_DIR}'")
endif()

set(SPLENDOR_DYNAMIC_LIBS "")

foreach(_name c m dl)
  if(EXISTS "${SPLENDOR_LIBDIR}/${_name}.so")
    list(APPEND SPLENDOR_DYNAMIC_LIBS "${SPLENDOR_LIBDIR}/${_name}.so")
  else()
    message(FATAL_ERROR "SPLENDOR_DYNAMIC_LIBC: missing ${_name}.so in ${SPLENDOR_LIBDIR}")
  endif()
endforeach()

foreach(_candidate "${SPLENDOR_LIBDIR}/libc++_shared.so" "${SPLENDOR_ARCH_DIR}/libc++_shared.so")
  if(EXISTS "${_candidate}")
    list(APPEND SPLENDOR_DYNAMIC_LIBS "${_candidate}")
    break()
  endif()
endforeach()

target_link_options(splendor_native PRIVATE
  "-L${SPLENDOR_LIBDIR}"
  "-Wl,-Bdynamic"
  "-Wl,--no-as-needed"
)

target_link_libraries(splendor_native PRIVATE
  ${SPLENDOR_DYNAMIC_LIBS}
)
# SPLENDOR_NATIVE_DYNAMIC_LIBC_END
