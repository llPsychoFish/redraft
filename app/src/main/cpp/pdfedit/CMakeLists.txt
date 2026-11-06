cmake_minimum_required(VERSION 3.22)
project(pdfedit LANGUAGES CXX)

# PDFium headers matching the AAR's Chromium build
# These must be vendored or fetched from the same Chromium commit as pdfiumandroid 3.0.4-alpha01
set(PDFIUM_INCLUDE_DIR "${CMAKE_SOURCE_DIR}/../../../pdfium-headers")

add_library(pdfedit SHARED
    pdfedit.cpp
    EditingDocument.cpp
    TextMutation.cpp
    FontLoading.cpp
    Save.cpp
)

target_include_directories(pdfedit PRIVATE
    ${PDFIUM_INCLUDE_DIR}
    ${CMAKE_SOURCE_DIR}
)

# Link against the AAR's libpdfium.so (prebuilt import)
# The AAR packages libpdfium.so for all ABIs; we import it as a prebuilt
find_library(PDFIUM_LIB pdfium PATHS
    "${CMAKE_SOURCE_DIR}/../../../pdfiumandroid-prebuilt/${ANDROID_ABI}"
    "${ANDROID_NDK}/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/${ANDROID_ABI}"
)

target_link_libraries(pdfedit
    ${PDFIUM_LIB}
    android
    log
)

# ABI filters match app/build.gradle.kts
set(CMAKE_ANDROID_ARCH_ABI "arm64-v8a;x86_64")