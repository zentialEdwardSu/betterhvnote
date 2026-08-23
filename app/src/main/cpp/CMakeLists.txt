cmake_minimum_required(VERSION 3.22)
project(betterhvnote_native LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(THIRD_PARTY_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party")

# Both vendored as git submodules pinned to a fixed commit rather than fetched
# at configure time, so the build stays reproducible offline (see plan doc).
# Disable their test/install machinery -- we only want the library targets,
# not GoogleTest/Fuzztest pulled into an Android build.
set(ABSL_ENABLE_INSTALL OFF CACHE BOOL "" FORCE)
set(ABSL_PROPAGATE_CXX_STD ON CACHE BOOL "" FORCE)
add_subdirectory("${THIRD_PARTY_DIR}/abseil-cpp" abseil-cpp EXCLUDE_FROM_ALL)

# Deliberately do NOT add_subdirectory the ink-stroke-modeler repo root: its
# top-level CMakeLists.txt unconditionally FetchContent-fetches its own copy
# of Abseil (and GoogleTest/Fuzztest) unless INK_STROKE_MODELER_FIND_ABSL is
# set, which would collide with the abseil-cpp submodule already added above
# (duplicate absl_* targets). The library targets themselves are defined in
# the ink_stroke_modeler/ subdirectory with no FetchContent of its own, so we
# add just that, replicating the handful of root-level setup steps it expects.
set(INK_STROKE_MODELER_BUILD_TESTING OFF)
set(INK_STROKE_MODELER_ENABLE_INSTALL OFF)
list(APPEND CMAKE_MODULE_PATH "${THIRD_PARTY_DIR}/ink-stroke-modeler/cmake")
include("${THIRD_PARTY_DIR}/ink-stroke-modeler/cmake/InkBazelEquivalents.cmake")
include_directories("${THIRD_PARTY_DIR}/ink-stroke-modeler")
add_subdirectory(
    "${THIRD_PARTY_DIR}/ink-stroke-modeler/ink_stroke_modeler"
    ink_stroke_modeler
    EXCLUDE_FROM_ALL
)

# The only shared object we produce: a thin JNI bridge that statically links
# ink-stroke-modeler (and, transitively, Abseil) into itself. JNI requires
# System.loadLibrary() to target a .so, so this bridge can't itself be static
# even though everything it wraps is -- see plan doc §1.
add_library(ink_stroke_modeler_jni SHARED
    stroke_modeler_jni.cc
)

target_link_libraries(ink_stroke_modeler_jni
    PRIVATE
    log
    InkStrokeModeler::stroke_modeler
)

# NDK r28+ already emits 16 KiB-compatible ELF LOAD segments by default. Keep the
# linker contract explicit so a local toolchain override cannot silently regress it.
target_link_options(ink_stroke_modeler_jni PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)
