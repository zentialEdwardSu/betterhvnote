cmake_minimum_required(VERSION 3.24)
project(notelink_windows LANGUAGES CXX)

# Ship the MSVC runtime inside each native binary so a clean Windows install does
# not require a separate Visual C++ Redistributable package.
if(MSVC)
    set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
endif()

add_library(notelink_windows SHARED notelink_windows.cpp)
target_compile_features(notelink_windows PRIVATE cxx_std_20)
target_compile_definitions(notelink_windows PRIVATE WIN32_LEAN_AND_MEAN NOMINMAX NOTELINK_WINDOWS_EXPORTS)
target_link_libraries(notelink_windows PRIVATE windowsapp crypt32 ole32)
set_target_properties(notelink_windows PROPERTIES OUTPUT_NAME "notelink_windows")

add_executable(notelink_firewall_action firewall_action.cpp)
target_compile_features(notelink_firewall_action PRIVATE cxx_std_20)
target_compile_definitions(notelink_firewall_action PRIVATE WIN32_LEAN_AND_MEAN NOMINMAX)
target_link_libraries(notelink_firewall_action PRIVATE ole32 oleaut32)
