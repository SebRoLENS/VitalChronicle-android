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

# Android NDK ships the Vulkan C loader/headers but not Vulkan-Hpp
# (vulkan/vulkan.hpp), which current llama.cpp requires. SPIR-V headers are
# likewise a host-side header dependency. Locate both on the build host. Do not
# pass /usr/include directly to the Android cross compiler: CMake treats it as
# an implicit host include path and drops it from the target compile command.
# Instead, copy only the platform-neutral Vulkan/SPIR-V header trees into a
# private staging directory used exclusively by ggml-vulkan.
set(VITAL_VULKAN_HEADERS_ROOT "$ENV{VULKAN_HPP_INCLUDE_DIR}")
if(NOT VITAL_VULKAN_HEADERS_ROOT)
    find_path(VITAL_VULKAN_HEADERS_ROOT
            NAMES vulkan/vulkan.hpp
            HINTS
                "$ENV{VULKAN_SDK}/Include"
                "$ENV{VULKAN_SDK}/include"
            PATHS
                /usr/include
                /usr/local/include
            NO_CMAKE_FIND_ROOT_PATH)
endif()

set(VITAL_SPIRV_HEADERS_ROOT "")
if(VITAL_VULKAN_HEADERS_ROOT AND
   EXISTS "${VITAL_VULKAN_HEADERS_ROOT}/spirv/unified1/spirv.hpp")
    set(VITAL_SPIRV_HEADERS_ROOT "${VITAL_VULKAN_HEADERS_ROOT}")
else()
    find_path(VITAL_SPIRV_HEADERS_ROOT
            NAMES spirv/unified1/spirv.hpp
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
    if(NOT VITAL_VULKAN_HEADERS_ROOT OR
       NOT EXISTS "${VITAL_VULKAN_HEADERS_ROOT}/vulkan/vulkan.hpp")
        message(FATAL_ERROR
            "GGML_VULKAN requires Vulkan-Hpp (vulkan/vulkan.hpp). "
            "Install the host Vulkan headers or set VULKAN_HPP_INCLUDE_DIR.")
    endif()
    if(NOT VITAL_SPIRV_HEADERS_ROOT OR
       NOT EXISTS "${VITAL_SPIRV_HEADERS_ROOT}/spirv/unified1/spirv.hpp")
        message(FATAL_ERROR
            "GGML_VULKAN requires SPIR-V headers (spirv/unified1/spirv.hpp). "
            "Install spirv-headers or provide them through the Vulkan SDK.")
    endif()

    set(VITAL_VULKAN_HEADERS_STAGE "${CMAKE_CURRENT_BINARY_DIR}/vital-vulkan-headers")
    file(MAKE_DIRECTORY "${VITAL_VULKAN_HEADERS_STAGE}")
    file(COPY "${VITAL_VULKAN_HEADERS_ROOT}/vulkan"
         DESTINATION "${VITAL_VULKAN_HEADERS_STAGE}")
    if(EXISTS "${VITAL_VULKAN_HEADERS_ROOT}/vk_video")
        file(COPY "${VITAL_VULKAN_HEADERS_ROOT}/vk_video"
             DESTINATION "${VITAL_VULKAN_HEADERS_STAGE}")
    endif()
    file(COPY "${VITAL_SPIRV_HEADERS_ROOT}/spirv"
         DESTINATION "${VITAL_VULKAN_HEADERS_STAGE}")

    target_include_directories(ggml-vulkan BEFORE PRIVATE
            "${VITAL_VULKAN_HEADERS_STAGE}")
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
