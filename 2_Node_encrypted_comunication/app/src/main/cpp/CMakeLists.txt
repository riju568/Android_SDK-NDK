cmake_minimum_required(VERSION 3.18.2)
project(CoreEngineNative LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
include(FetchContent)
FetchContent_Declare(
    imgui
    GIT_REPOSITORY https://github.com/ocornut/imgui.git
    GIT_TAG 
)
FetchContent_MakeAvailable(imgui)
find_library(LOG_LIB log)
find_library(EGL_LIB EGL)
find_library(GLES3_LIB GLESv3)
find_library(ANDROID_LIB android)
set(WEBRTC_INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/webrtc/include" CACHE PATH "WebRTC headers path")
set(WEBRTC_LIBRARY "${CMAKE_CURRENT_SOURCE_DIR}/third_party/webrtc/lib/${ANDROID_ABI}/libwebrtc.a" CACHE FILEPATH "WebRTC prebuilt static lib")
set(PROJECT_SOURCES
    # Core Engine & JNI Bindings
    ${CMAKE_CURRENT_SOURCE_DIR}/JNIBridge.cpp

    # ImGui Core Sources (Downloaded via FetchContent)
    ${imgui_SOURCE_DIR}/imgui.cpp
    ${imgui_SOURCE_DIR}/imgui_draw.cpp
    ${imgui_SOURCE_DIR}/imgui_tables.cpp
    ${imgui_SOURCE_DIR}/imgui_widgets.cpp
    ${imgui_SOURCE_DIR}/backends/imgui_impl_android.cpp
    ${imgui_SOURCE_DIR}/backends/imgui_impl_opengl3.cpp
)
add_library(core_engine SHARED ${PROJECT_SOURCES})
target_include_directories(core_engine PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${imgui_SOURCE_DIR}
    ${imgui_SOURCE_DIR}/backends
    ${WEBRTC_INCLUDE_DIR}
)
target_link_libraries(core_engine PRIVATE
    ${LOG_LIB}
    ${EGL_LIB}
    ${GLES3_LIB}
    ${ANDROID_LIB}
    ${WEBRTC_LIBRARY}
)
include(GNUInstallDirs)
install(TARGETS core_engine
    LIBRARY DESTINATION ${CMAKE_INSTALL_LIBDIR}
    ARCHIVE DESTINATION ${CMAKE_INSTALL_LIBDIR}
    RUNTIME DESTINATION ${CMAKE_INSTALL_BINDIR}
)

install(FILES
    ${CMAKE_CURRENT_SOURCE_DIR}/ChatEngine.hpp
    ${CMAKE_CURRENT_SOURCE_DIR}/FirebaseRealtimeClient.hpp
    ${CMAKE_CURRENT_SOURCE_DIR}/ChatEngine.hpp
    ${CMAKE_CURRENT_SOURCE_DIR}/SecurityEngine.hpp
    ${CMAKE_CURRENT_SOURCE_DIR}/PlannerSyncEngine.hpp
    ${CMAKE_CURRENT_SOURCE_DIR}/WebRTCSignalingEngine.hpp
    DESTINATION ${CMAKE_INSTALL_INCLUDEDIR}/native-lib.cpp
)