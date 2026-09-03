cmake_minimum_required(VERSION 3.31.6)

project("ai-chat" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED true)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

if(DEFINED ANDROID_ABI)
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_SYSTEM_ARCH "ARM")
        set(GGML_CPU_KLEIDIAI ON)
        set(GGML_OPENMP ON)
    else()
        message(FATAL_ERROR "Unsupported VitalChronicle ABI: ${ANDROID_ABI}")
    endif()
endif()

# Android NDK ships the Vulkan C API, but not Vulkan-Hpp (vulkan/vulkan.hpp),
# which current llama.cpp requires. Locate the header on the build host and add
# it only to the Vulkan backend; the backend still links against Android's NDK
# Vulkan loader. CI provides VULKAN_HPP_INCLUDE_DIR from libvulkan-dev.
set(VITAL_VULKAN_HPP_INCLUDE_DIR "$ENV{VULKAN_HPP_INCLUDE_DIR}")
if(NOT VITAL_VULKAN_HPP_INCLUDE_DIR)
    find_path(VITAL_VULKAN_HPP_INCLUDE_DIR
            NAMES vulkan/vulkan.hpp
            HINTS
                "$ENV{VULKAN_SDK}/Include"
                "$ENV{VULKAN_SDK}/include"
            PATHS
                /usr/include
                /usr/local/include
            NO_CMAKE_FIND_ROOT_PATH)
endif()

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp)
add_subdirectory(${LLAMA_SRC} build-llama)

if(TARGET ggml-vulkan)
    if(NOT VITAL_VULKAN_HPP_INCLUDE_DIR OR
       NOT EXISTS "${VITAL_VULKAN_HPP_INCLUDE_DIR}/vulkan/vulkan.hpp")
        message(FATAL_ERROR
            "GGML_VULKAN requires Vulkan-Hpp (vulkan/vulkan.hpp). "
            "Install the host Vulkan headers or set VULKAN_HPP_INCLUDE_DIR.")
    endif()
    target_include_directories(ggml-vulkan SYSTEM PRIVATE
            "${VITAL_VULKAN_HPP_INCLUDE_DIR}")
endif()

add_library(${CMAKE_PROJECT_NAME} SHARED vital_ai_chat.cpp)

target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE
        GGML_SYSTEM_ARCH=${GGML_SYSTEM_ARCH}
        GGML_CPU_KLEIDIAI=$<BOOL:${GGML_CPU_KLEIDIAI}>
        GGML_OPENMP=$<BOOL:${GGML_OPENMP}>)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        ${LLAMA_SRC}
        ${LLAMA_SRC}/common
        ${LLAMA_SRC}/include
        ${LLAMA_SRC}/ggml/include
        ${LLAMA_SRC}/ggml/src)

target_link_libraries(${CMAKE_PROJECT_NAME}
        llama
        llama-common
        android
        log)
