cmake_minimum_required(VERSION 3.18.1)
project("splendor_native" C CXX)

# Native crash breadcrumbs are on by default for every exported JNI entry.
add_compile_definitions(SPLENDOR_BREADCRUMBS=1)

add_library(splendor_native SHARED
  native_breadcrumb.c
  # DEAD-REMOVE: native_input.cpp removed — its only JNI functions
  # (nativeInjectTap, nativeInjectSwipe) had zero Kotlin callers.
  native_kicking_posture.c
  native_agility_physics.c
  native_registration.c
  native_arbitration.c
  native_gameplay_compute.c
  native_vision_blob.c
  native_vision_preprocessor.c
  # Phase N (N-3/N-4): native_frame_analyzer.c + native_predictive_frame_interp.c
  # removed — their only JNI functions (nativeAnalyzeFrame,
  # nativePredictiveFrameInterpImpl) had zero Kotlin callers. The C files
  # themselves go in the deletion commit; the build stops compiling them here.
  native_direct_input_hook.c
  native_stopper_matrix.c




  native_true_shot.c
  native_optical_variance.c
  native_sa_corrector.c

  native_critical_vector.c
  # 6a: native_speed_compensation.c removed — the TU held only two static
  # helpers (branchless_coerce/select) with zero JNI exports, no Kotlin
  # declaration, and no in-TU users. (Delete the file via delete_file;
  # push_files cannot delete.)
  native_tactical_engines.c

)

find_library(log-lib log)
find_library(android-lib android)


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
  "-Wl,-z,max-page-size=16384"
  "-Wl,-z,common-page-size=16384"
)

target_link_libraries(splendor_native PRIVATE
  ${log-lib}
  ${android-lib}

)
# SPLENDOR_NATIVE_LINK_SHIM_V9_END

# SPLENDOR_V31A_SUPPRESS_UNUSED_LINK_PATH_WARNING_BEGIN
target_compile_options(splendor_native PRIVATE -Wno-unused-command-line-argument)
# SPLENDOR_V31A_SUPPRESS_UNUSED_LINK_PATH_WARNING_END
