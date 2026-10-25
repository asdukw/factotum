cmake_minimum_required(VERSION 3.22.1)

# libnode.so 依赖 libc++_shared.so；必须在 project() 前设置，NDK toolchain 才会用共享 STL
set(ANDROID_STL c++_shared)

project(factotum-node CXX)

# Node 24 的头文件（cppgc）要求 C++20
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(native-lib SHARED src/main/cpp/native-lib.cpp)

include_directories(libnode/include/node)

add_library(libnode SHARED IMPORTED)
set_target_properties(libnode PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/libnode/bin/${ANDROID_ABI}/libnode.so)

find_library(log-lib log)

target_link_libraries(native-lib libnode ${log-lib})
