cmake_minimum_required(VERSION 3.16)
# C as well as C++: a Geant4 built with HDF5 has Geant4Config.cmake call find_package(HDF5), whose
# checks compile C. With C++ alone the configuration stopped there ("try_compile() works only for
# enabled languages"), on every Linux, macOS and WSL Geant4 that has HDF5. Geant4's examples enable both.
project(geant4-bridge VERSION 1.0.0 LANGUAGES C CXX)

# geant4-bridge: Sphere's Geant4 engine. It needs Geant4 (any 11.x: an
# installation, a conda environment, a CVMFS view) and nothing else: no ROOT,
# no third-party library. Point CMake at Geant4 with -DGeant4_DIR=<prefix>/lib/cmake/Geant4
# or CMAKE_PREFIX_PATH; Sphere's Geant4BridgeCompiler does it from settings.conf,
# geant4-config or the active conda / virtual environment.
#
# -DG4BRIDGE_WITHOUT_GEANT4=ON builds the same engine without Geant4: both
# transports and the common commands, every Geant4 command answering that it
# is missing. That is how the channel is tested where Geant4 is not installed.

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()

option(G4BRIDGE_WITHOUT_GEANT4 "Build the transport-test engine, without Geant4" OFF)

set(SOURCES src/main.cpp src/g4b_shm.cpp src/g4b_common.cpp)
find_package(Threads REQUIRED)

if(G4BRIDGE_WITHOUT_GEANT4)
  message(STATUS "[Sphere] geant4-bridge without Geant4 (transport test engine)")
  add_executable(geant4-bridge ${SOURCES} src/g4b_nogeant4.cpp)
else()
  # gdml is the one component the bridge needs; ui and vis stay out
  find_package(Geant4 REQUIRED COMPONENTS gdml)
  message(STATUS "[Sphere] Geant4 ${Geant4_VERSION} found in ${Geant4_DIR}")
  add_executable(geant4-bridge ${SOURCES} src/g4b_geant4.cpp)
  target_link_libraries(geant4-bridge PRIVATE ${Geant4_LIBRARIES})
  if(Geant4_INCLUDE_DIRS)
    target_include_directories(geant4-bridge SYSTEM PRIVATE ${Geant4_INCLUDE_DIRS})
  endif()
  if(Geant4_DEFINITIONS)
    target_compile_definitions(geant4-bridge PRIVATE ${Geant4_DEFINITIONS})
  endif()
  if(Geant4_CXX_FLAGS)
    separate_arguments(_g4flags UNIX_COMMAND "${Geant4_CXX_FLAGS}")
    target_compile_options(geant4-bridge PRIVATE ${_g4flags})
  endif()
endif()

target_include_directories(geant4-bridge PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/inc)
target_link_libraries(geant4-bridge PRIVATE Threads::Threads)

if(MSVC)
  target_compile_options(geant4-bridge PRIVATE /W3 /O2 /permissive- /utf-8)
else()
  target_compile_options(geant4-bridge PRIVATE -Wall -Wextra -O2)
endif()

install(TARGETS geant4-bridge RUNTIME DESTINATION .)
