# Google's libwebp, for maximum-effort lossless sticker encoding (Android's built-in encoder
# only does a fast lossless pass: about twice the bytes for identical pixels).
cmake_minimum_required(VERSION 3.22.1)
project(stickerwebp C)

include(FetchContent)

# Only the library: none of libwebp's command-line tools, mux or extras.
foreach(opt ANIM_UTILS CWEBP DWEBP GIF2WEBP IMG2WEBP VWEBP WEBPINFO LIBWEBPMUX WEBPMUX EXTRAS)
    set(WEBP_BUILD_${opt} OFF CACHE BOOL "" FORCE)
endforeach()
set(WEBP_NEAR_LOSSLESS OFF CACHE BOOL "" FORCE)

# Pinned release, verified by hash. Bump both together.
FetchContent_Declare(
    libwebp
    URL https://github.com/webmproject/libwebp/archive/refs/tags/v1.6.0.tar.gz
    URL_HASH SHA256=93a852c2b3efafee3723efd4636de855b46f9fe1efddd607e1f42f60fc8f2136
)
FetchContent_MakeAvailable(libwebp)

add_library(stickerwebp SHARED webp_jni.c)
target_link_libraries(stickerwebp PRIVATE webp)

# Small: size-optimised, and unused code dropped at link time.
target_compile_options(stickerwebp PRIVATE -Os -ffunction-sections -fdata-sections)
target_compile_options(webp PRIVATE -Os -ffunction-sections -fdata-sections)
target_link_options(stickerwebp PRIVATE -Wl,--gc-sections)
