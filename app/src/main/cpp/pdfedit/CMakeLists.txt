cmake_minimum_required(VERSION 3.22.1)
project(pdfedit LANGUAGES CXX)

# Vendored PDFium public headers matching the C API exported by the
# pdfiumandroid-core 3.0.4-alpha01 AAR (see pdfium-headers/README.md).
set(PDFIUM_INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../pdfium-headers")

if(NOT DEFINED PDFIUM_JNI_DIR)
    message(FATAL_ERROR "PDFIUM_JNI_DIR is not set; Gradle extracts the AAR's libpdfium.so first")
endif()

set(PDFIUM_LIBRARY "${PDFIUM_JNI_DIR}/${ANDROID_ABI}/libpdfium.so")
if(NOT EXISTS "${PDFIUM_LIBRARY}")
    message(FATAL_ERROR "libpdfium.so not found at ${PDFIUM_LIBRARY}")
endif()

# Link against the SAME libpdfium.so the renderer loads. It is imported, never
# copied: Gradle packages the AAR's own copy into the APK.
add_library(pdfium SHARED IMPORTED)
set_target_properties(pdfium PROPERTIES IMPORTED_LOCATION "${PDFIUM_LIBRARY}")

add_library(pdfedit SHARED
    pdfedit.cpp
    EditingDocument.cpp
    TextMutation.cpp
    FontLoading.cpp
    Save.cpp
)

target_include_directories(pdfedit PRIVATE
    ${PDFIUM_INCLUDE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_compile_features(pdfedit PRIVATE cxx_std_17)

target_link_libraries(pdfedit
    pdfium
    android
    log
)
