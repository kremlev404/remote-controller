cmake_minimum_required(VERSION 3.20)
project(screencast LANGUAGES CXX)

if(NOT WIN32)
    message(FATAL_ERROR "screencast is Windows-only")
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
# The static CRT: the app must start on a PC without the Visual C++ redistributable.
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")

set(SCREENCAST_JDK_HOME "" CACHE PATH "JDK that holds include/jni.h")
set(SCREENCAST_OUTPUT_DIR "${CMAKE_BINARY_DIR}/natives" CACHE PATH "Directory for screencast.dll")

add_library(screencast SHARED
    DesktopDuplicator.cpp
    FrameConverter.cpp
    H264Encoder.cpp
    Screencast.cpp
)

target_include_directories(screencast PRIVATE
    "${SCREENCAST_JDK_HOME}/include"
    "${SCREENCAST_JDK_HOME}/include/win32"
)

target_compile_definitions(screencast PRIVATE WIN32_LEAN_AND_MEAN NOMINMAX UNICODE _UNICODE)

target_compile_options(screencast PRIVATE /W4 /permissive- /sdl /guard:cf /GS /Zc:__cplusplus)

# /DEPENDENTLOADFLAG 0x800: imports load from System32 only, never from the app or current folder.
target_link_options(screencast PRIVATE
    /guard:cf
    /CETCOMPAT
    /DYNAMICBASE
    /HIGHENTROPYVA
    /NXCOMPAT
    /DEPENDENTLOADFLAG:0x800
)

target_link_libraries(screencast PRIVATE d3d11 dxgi mfplat mfuuid mf strmiids ole32)

set_target_properties(screencast PROPERTIES
    OUTPUT_NAME "screencast"
    RUNTIME_OUTPUT_DIRECTORY "${SCREENCAST_OUTPUT_DIR}"
    RUNTIME_OUTPUT_DIRECTORY_DEBUG "${SCREENCAST_OUTPUT_DIR}"
    RUNTIME_OUTPUT_DIRECTORY_RELEASE "${SCREENCAST_OUTPUT_DIR}"
    RUNTIME_OUTPUT_DIRECTORY_RELWITHDEBINFO "${SCREENCAST_OUTPUT_DIR}"
    RUNTIME_OUTPUT_DIRECTORY_MINSIZEREL "${SCREENCAST_OUTPUT_DIR}"
)
