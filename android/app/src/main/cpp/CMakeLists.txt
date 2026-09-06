cmake_minimum_required(VERSION 3.22.1)
project(shackcq_jni LANGUAGES CXX)

set(CORE_ROOT ../../../../../core)
get_filename_component(HAMLIB_SOURCE_ROOT "${CORE_ROOT}/third_party/hamlib" ABSOLUTE)
get_filename_component(HAMLIB_BUILD_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../build/hamlib" ABSOLUTE)
file(GLOB SHACKCQ_SGP4_SOURCES CONFIGURE_DEPENDS ${CORE_ROOT}/third_party/sgp4/libsgp4/*.cc)
get_filename_component(FLEX_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../rust/shackcq-flex" ABSOLUTE)
if(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(FLEX_RUST_TARGET armv7-linux-androideabi)
elseif(ANDROID_ABI STREQUAL "arm64-v8a")
    set(FLEX_RUST_TARGET aarch64-linux-android)
elseif(ANDROID_ABI STREQUAL "x86")
    set(FLEX_RUST_TARGET i686-linux-android)
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(FLEX_RUST_TARGET x86_64-linux-android)
else()
    message(FATAL_ERROR "Unsupported Android ABI for shackcq-flex: ${ANDROID_ABI}")
endif()
add_library(shackcq_flex STATIC IMPORTED)
set_target_properties(shackcq_flex PROPERTIES IMPORTED_LOCATION
    ${FLEX_ROOT}/target/${FLEX_RUST_TARGET}/release/libshackcq_flex.a)
add_library(shackcq_hamlib STATIC IMPORTED)
set_target_properties(shackcq_hamlib PROPERTIES IMPORTED_LOCATION
    ${HAMLIB_BUILD_ROOT}/${ANDROID_ABI}/libhamlib.a)
add_library(shackcq SHARED
    native_bridge.cpp
    native_rx_dsp.cpp
    native_local_receiver.cpp
    hamclock_propagation_bridge.cpp
    hamlib/hamlib_jni.cpp
    ${CORE_ROOT}/src/core.cpp
    ${CORE_ROOT}/src/features.cpp
    ${CORE_ROOT}/src/local_receiver.cpp
    ${CORE_ROOT}/src/tci.cpp
    ${CORE_ROOT}/src/satellite.cpp
    ${CORE_ROOT}/src/propagation/p533.cpp
    ${CORE_ROOT}/portable/src/adif.cpp
    ${CORE_ROOT}/portable/src/cat_parser.cpp
    ${CORE_ROOT}/portable/src/cty.cpp
    ${CORE_ROOT}/portable/src/dx_analysis.cpp
    ${CORE_ROOT}/portable/src/operator_intel.cpp
    ${CORE_ROOT}/portable/src/panadapter_dsp.cpp
    ${CORE_ROOT}/portable/src/protocol.cpp
    ${CORE_ROOT}/portable/src/spot.cpp
    ${CORE_ROOT}/portable/src/sync_queue.cpp
    ${CORE_ROOT}/portable/src/wsjtx_protocol.cpp
    ${SHACKCQ_SGP4_SOURCES})
target_include_directories(shackcq PRIVATE
    ${HAMLIB_BUILD_ROOT}/${ANDROID_ABI}/include
    ${HAMLIB_SOURCE_ROOT}/include
    ${CORE_ROOT}/include
    ${CORE_ROOT}/portable/include
    ${CORE_ROOT}/third_party/sgp4/libsgp4
    ${FLEX_ROOT}/include)
target_link_libraries(shackcq PRIVATE shackcq_hamlib shackcq_flex android log m)
target_compile_features(shackcq PRIVATE cxx_std_17)
target_compile_options(shackcq PRIVATE -Wall -Wextra -Wpedantic -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(shackcq PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
