cmake_minimum_required(VERSION 3.20)
# ScreenCaptureKit starts here. An older Mac refuses the library and the app keeps working without it.
set(CMAKE_OSX_DEPLOYMENT_TARGET "12.3" CACHE STRING "Oldest macOS the library runs on")
project(screencast LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
# The static CRT: the app must start on a PC without the Visual C++ redistributable.
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")

set(SCREENCAST_JDK_HOME "" CACHE PATH "JDK that holds include/jni.h")
set(SCREENCAST_OUTPUT_DIR "${CMAKE_BINARY_DIR}/natives" CACHE PATH "Directory for the built library")

if(WIN32)
    add_library(screencast SHARED
        DesktopCapture.cpp
        DesktopDuplicator.cpp
        FrameConverter.cpp
        GraphicsCapture.cpp
        H264Encoder.cpp
        PixelReader.cpp
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

    target_link_libraries(screencast PRIVATE d3d11 dxgi mfplat mfuuid mf strmiids ole32 runtimeobject)
elseif(APPLE)
    enable_language(OBJCXX)
    set(CMAKE_OBJCXX_STANDARD 17)
    set(CMAKE_OBJCXX_STANDARD_REQUIRED ON)

    add_library(screencast SHARED
        posix/Session.cpp
        macos/MacSession.mm
    )

    target_include_directories(screencast PRIVATE
        "${SCREENCAST_JDK_HOME}/include"
        "${SCREENCAST_JDK_HOME}/include/darwin"
    )

    target_compile_options(screencast PRIVATE -Wall -Wextra -fvisibility=hidden $<$<COMPILE_LANGUAGE:OBJCXX>:-fobjc-arc>)

    target_link_libraries(screencast PRIVATE
        "-framework CoreFoundation"
        "-framework CoreGraphics"
        "-framework CoreMedia"
        "-framework CoreVideo"
        "-framework Foundation"
        "-framework ScreenCaptureKit"
        "-framework VideoToolbox"
    )
else()
    find_package(X11 REQUIRED)
    if(NOT X11_XShm_FOUND)
        message(FATAL_ERROR "screencast needs libxext-dev for X11 shared memory capture")
    endif()

    add_library(screencast SHARED
        posix/Session.cpp
        linux/GstEncoder.cpp
        linux/LinuxSession.cpp
    )

    target_include_directories(screencast PRIVATE
        "${SCREENCAST_JDK_HOME}/include"
        "${SCREENCAST_JDK_HOME}/include/linux"
        ${X11_INCLUDE_DIR}
    )

    target_compile_options(screencast PRIVATE -Wall -Wextra -fvisibility=hidden -O3 -fstack-protector-strong)

    # GStreamer stays out of this list: it is opened at run time, and only if the PC has it.
    target_link_libraries(screencast PRIVATE ${X11_LIBRARIES} ${X11_Xext_LIB} ${CMAKE_DL_LIBS})
    target_link_options(screencast PRIVATE -Wl,-z,relro,-z,now,-z,noexecstack)
endif()

set_target_properties(screencast PROPERTIES
    OUTPUT_NAME "screencast"
    RUNTIME_OUTPUT_DIRECTORY "${SCREENCAST_OUTPUT_DIR}"
    RUNTIME_OUTPUT_DIRECTORY_DEBUG "${SCREENCAST_OUTPUT_DIR}"
    RUNTIME_OUTPUT_DIRECTORY_RELEASE "${SCREENCAST_OUTPUT_DIR}"
    RUNTIME_OUTPUT_DIRECTORY_RELWITHDEBINFO "${SCREENCAST_OUTPUT_DIR}"
    RUNTIME_OUTPUT_DIRECTORY_MINSIZEREL "${SCREENCAST_OUTPUT_DIR}"
    LIBRARY_OUTPUT_DIRECTORY "${SCREENCAST_OUTPUT_DIR}"
    LIBRARY_OUTPUT_DIRECTORY_RELEASE "${SCREENCAST_OUTPUT_DIR}"
)
