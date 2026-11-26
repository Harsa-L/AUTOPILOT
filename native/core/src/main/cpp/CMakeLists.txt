cmake_minimum_required(VERSION 3.22.1)
project(autopilot_native LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(
    autopilot_native
    SHARED
    protocol/protocol_packets.cpp
    communication/native_session.cpp
    processing/video_reassembler.cpp
    algorithms/vision_engine.cpp
    jni/autopilot_jni.cpp
)

target_include_directories(
    autopilot_native
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(
    autopilot_native
    PRIVATE
    ${log-lib}
    ${android-lib}
)
