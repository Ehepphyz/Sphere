cmake_minimum_required(VERSION 3.20)
project(root-bridge VERSION 1.0.0 LANGUAGES CXX C)

# Enforce C++20 standard
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Default to Release build configuration if unspecified
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()

# -----------------------------------------------------------------------------
# ROOT & System Dependencies
# -----------------------------------------------------------------------------
# Tree and TreePlayer were missing. commands/ uses TTree, TBranch, TLeaf and
# TTreeCache (libTree), and TTreeFormula for the selection cut, which lives in
# libTreePlayer rather than libTree. Without both, the build gets all the way to
# the link step and then fails on undefined symbols.
find_package(ROOT REQUIRED COMPONENTS Core RIO Net Hist Tree TreePlayer ROOTNTuple)
include(${ROOT_USE_FILE})

find_package(Threads REQUIRED)

# Locate libdeflate for fast decompression tasks
find_package(PkgConfig QUIET)
if(PKG_CONFIG_FOUND)
  pkg_check_modules(LIBDEFLATE QUIET libdeflate)
endif()

if(NOT LIBDEFLATE_FOUND)
  find_library(LIBDEFLATE_LIBRARIES NAMES deflate libdeflate)
endif()

# zstd and lz4 were never looked for, although inc/DecompressorPool.h calls
# ZSTD_decompress, LZ4_decompress_safe and friends directly. They resolved only
# by accident, through whatever ROOT happened to pull in.
find_library(ZSTD_LIBRARY NAMES zstd REQUIRED)
find_library(LZ4_LIBRARY NAMES lz4 REQUIRED)

# -----------------------------------------------------------------------------
# Discover Sources & Headers
# -----------------------------------------------------------------------------
file(GLOB_RECURSE HEADERS CONFIGURE_DEPENDS
    "${CMAKE_CURRENT_SOURCE_DIR}/inc/*.h"
    "${CMAKE_CURRENT_SOURCE_DIR}/commands/*.h"
)

file(GLOB_RECURSE SOURCES CONFIGURE_DEPENDS
    "${CMAKE_CURRENT_SOURCE_DIR}/src/*.cpp"
    "${CMAKE_CURRENT_SOURCE_DIR}/commands/*.cpp"
)

# Fallback check: default to main.cpp if GLOB finds no explicit source targets
if(NOT SOURCES)
  set(SOURCES src/main.cpp)
endif()

# -----------------------------------------------------------------------------
# Target Executable Configuration
# -----------------------------------------------------------------------------
add_executable(root-bridge ${HEADERS} ${SOURCES})

# -----------------------------------------------------------------------------
# ROOT v7 / RNTuple Optional Header Detection
# -----------------------------------------------------------------------------
include(CheckIncludeFileCXX)

# Save original CMAKE_REQUIRED_INCLUDES to preserve state
set(OLD_CMAKE_REQUIRED_INCLUDES ${CMAKE_REQUIRED_INCLUDES})
set(CMAKE_REQUIRED_INCLUDES ${ROOT_INCLUDE_DIRS})

# Check whether ROOT/RPageSink.hxx exists in the user's ROOT installation
check_include_file_cxx("ROOT/RPageSink.hxx" HAVE_ROOT_RPAGESINK)

# Restore previous include path configuration
set(CMAKE_REQUIRED_INCLUDES ${OLD_CMAKE_REQUIRED_INCLUDES})

if(HAVE_ROOT_RPAGESINK)
  message(STATUS "[Sphere] CERN ROOT v7 / RPageSink header detected. Enabling experimental RNTuple support.")
  target_compile_definitions(root-bridge PRIVATE HAS_ROOT_V7_NTUPLE)
else()
  message(STATUS "[Sphere] CERN ROOT v7 / RPageSink header NOT found. Falling back to classic TTree / TFile engines.")
endif()

# -----------------------------------------------------------------------------
# Optional io_uring Support Detection (Linux / WSL2)
# -----------------------------------------------------------------------------
if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
  if(PKG_CONFIG_FOUND)
    pkg_check_modules(LIBURING QUIET liburing)
  endif()

  if(LIBURING_FOUND)
    message(STATUS "[Sphere] io_uring detected. Enabling kernel-bypass I/O support.")
    target_compile_definitions(root-bridge PRIVATE HAVE_IO_URING)
  else()
    message(STATUS "[Sphere] liburing not found. Falling back to POSIX / Async Worker Pool.")
  endif()
endif()

# -----------------------------------------------------------------------------
# Hardware Architecture & SIMD Detection (ARM64 / SVE / x86_64)
# -----------------------------------------------------------------------------
include(CheckCXXCompilerFlag)

# Optional user-facing toggle to enable/disable ARM SVE optimization
option(ENABLE_SVE "Enable ARM Scalable Vector Extension (SVE) support" ON)

if(CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64|arm64|ARM64")
  message(STATUS "[Sphere] Architecture detected: ARM64")

  if(ENABLE_SVE)
    # Safely test if compiler supports ARM SVE headers and flags
    set(OLD_CMAKE_REQUIRED_FLAGS ${CMAKE_REQUIRED_FLAGS})
    set(CMAKE_REQUIRED_FLAGS "-march=armv8-a+sve")

    check_include_file_cxx("arm_sve.h" HAVE_ARM_SVE_H)
    set(CMAKE_REQUIRED_FLAGS ${OLD_CMAKE_REQUIRED_FLAGS})

    if(HAVE_ARM_SVE_H)
      message(STATUS "[Sphere] ARM SVE header (<arm_sve.h>) detected. Enabling SVE SIMD paths.")
      target_compile_definitions(root-bridge PRIVATE HAS_SVE_HEADERS)
      target_compile_options(root-bridge PRIVATE -march=armv8-a+sve)
    else()
      message(STATUS "[Sphere] ARM SVE header NOT supported by current toolchain. SVE disabled.")
    endif()
  endif()

elseif(CMAKE_SYSTEM_PROCESSOR MATCHES "x86_64|amd64|AMD64")
  message(STATUS "[Sphere] Architecture detected: x86_64")

  # -march=native is OFF by default now.
  #
  # Wide SIMD and the CRC32C instruction are chosen at RUN time through
  # utils::cpu_capabilities(), and the functions that use them carry their own
  # __attribute__((target(...))). Enabling an ISA globally lets the compiler emit
  # those instructions anywhere, so the binary traps on any machine older than
  # the one it was built on -- including the rest of a heterogeneous cluster.
  #
  # Turn it back on with -DENABLE_MARCH_NATIVE=ON for a build that never leaves
  # this host.
  option(ENABLE_MARCH_NATIVE "Tune for the build host's exact CPU" OFF)
  if(ENABLE_MARCH_NATIVE)
    check_cxx_compiler_flag("-march=native" COMPILER_SUPPORTS_MARCH_NATIVE)
    if(COMPILER_SUPPORTS_MARCH_NATIVE AND NOT CMAKE_SYSTEM_NAME STREQUAL "Darwin")
      target_compile_options(root-bridge PRIVATE -march=native)
    endif()
  endif()
endif()

# -----------------------------------------------------------------------------
# Compiler & Optimization Flags
# -----------------------------------------------------------------------------
if(MSVC)
  target_compile_options(root-bridge PRIVATE
    /W4
    /O2                     # Maximum speed optimization
    /permissive-            # Enforce strict conformance
    /utf-8
  )
elseif(CMAKE_CXX_COMPILER_ID MATCHES "GNU|Clang")
  target_compile_options(root-bridge PRIVATE
    -Wall
    -Wextra
    -Wpedantic
    -O3                     # Maximum optimization level
    -flto                   # Link-Time Optimization (LTO)
    -fno-omit-frame-pointer # Retain frame pointers for profiling tools
    -fstrict-aliasing
    -pipe
  )
endif()

# Linker flags for Linux environments
if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
  target_link_options(root-bridge PRIVATE
    -flto
    -Wl,--no-as-needed
  )

  # Attempt to use the GNU Gold linker if available
  check_cxx_compiler_flag("-fuse-ld=gold" COMPILER_SUPPORTS_GOLD)
  if(COMPILER_SUPPORTS_GOLD)
    target_link_options(root-bridge PRIVATE -fuse-ld=gold)
  endif()
endif()

# -----------------------------------------------------------------------------
# Include Directories
# -----------------------------------------------------------------------------
target_include_directories(root-bridge PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/inc
    ${CMAKE_CURRENT_SOURCE_DIR}/commands
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${ROOT_INCLUDE_DIRS}
)

if(LIBDEFLATE_INCLUDE_DIRS)
  target_include_directories(root-bridge PRIVATE ${LIBDEFLATE_INCLUDE_DIRS})
endif()

if(LIBURING_FOUND)
  target_include_directories(root-bridge PRIVATE ${LIBURING_INCLUDE_DIRS})
endif()

# -----------------------------------------------------------------------------
# Library Linkage
# -----------------------------------------------------------------------------
target_link_libraries(root-bridge PRIVATE
    ${ROOT_LIBRARIES}
    ROOTNTuple
    ${LIBDEFLATE_LIBRARIES}
    ${ZSTD_LIBRARY}
    ${LZ4_LIBRARY}
    Threads::Threads
)

# Linux-specific dependencies (POSIX Realtime library for shared memory)
if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
  target_link_libraries(root-bridge PRIVATE rt)
  if(LIBURING_FOUND)
    target_link_libraries(root-bridge PRIVATE ${LIBURING_LIBRARIES})
  endif()
endif()