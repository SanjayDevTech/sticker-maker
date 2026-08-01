# Google's libwebp, for maximum-effort lossless sticker encoding and animated stickers (Android's built-in encoder
# only does a fast lossless pass: about twice the bytes for identical pixels).
cmake_minimum_required(VERSION 3.22.1)
project(stickerwebp C)

include(FetchContent)

# Size: applied to libwebp's own sources too (they build as separate object libraries, so
# per-target options wouldn't reach them). Hidden visibility + link-time optimisation let the
# linker drop everything our two JNI functions don't reach, including the whole decoder.
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -ffunction-sections -fdata-sections -fvisibility=hidden -flto=thin")
# The NDK appends -O2/-O3 per build type after CMAKE_C_FLAGS, so the size level goes here.
foreach(type RELEASE RELWITHDEBINFO MINSIZEREL)
    set(CMAKE_C_FLAGS_${type} "-Oz -DNDEBUG")
endforeach()

# Only the encoder and the mux library (which joins frames into animated stickers): none of
# libwebp's command-line tools or extras.
foreach(opt ANIM_UTILS CWEBP DWEBP GIF2WEBP IMG2WEBP VWEBP WEBPINFO WEBPMUX EXTRAS)
    set(WEBP_BUILD_${opt} OFF CACHE BOOL "" FORCE)
endforeach()
set(WEBP_NEAR_LOSSLESS OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_LIBWEBPMUX ON CACHE BOOL "" FORCE)

# Pinned release, verified by hash. Bump both together.
FetchContent_Declare(
    libwebp
    URL https://github.com/webmproject/libwebp/archive/refs/tags/v1.6.0.tar.gz
    URL_HASH SHA256=93a852c2b3efafee3723efd4636de855b46f9fe1efddd607e1f42f60fc8f2136
)
FetchContent_MakeAvailable(libwebp)

add_library(stickerwebp SHARED webp_jni.c)
target_link_libraries(stickerwebp PRIVATE libwebpmux webp)

# Only the JNI entry points are exported; unused code and identical functions are folded away.
target_link_options(stickerwebp PRIVATE -flto=thin -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,--icf=all)
