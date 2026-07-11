// Bridge from Kotlin (editor/WebpNative.kt) to libwebp's lossless encoder.
#include <jni.h>
#include "webp/encode.h"

// Encodes ARGB pixels (Java ints, 0xAARRGGBB, not premultiplied) as lossless WebP at the given
// effort level (0 fast .. 9 smallest). Returns the file bytes, or null on failure.
JNIEXPORT jbyteArray JNICALL
Java_com_example_stickermaker_editor_WebpNative_encodeLossless(
        JNIEnv *env, jclass clazz, jintArray argb, jint width, jint height, jint level) {
    (void) clazz;
    WebPConfig config;
    if (!WebPConfigInit(&config) || !WebPConfigLosslessPreset(&config, level)) return NULL;
    // Colour under fully transparent pixels may be simplified: invisible, and smaller.
    config.exact = 0;
    // Lets libwebp try its lossless strategies on a second thread.
    config.thread_level = 1;

    WebPPicture picture;
    if (!WebPPictureInit(&picture)) return NULL;
    picture.use_argb = 1;
    picture.width = width;
    picture.height = height;

    jint *pixels = (*env)->GetIntArrayElements(env, argb, NULL);
    if (pixels == NULL) return NULL;
    // On little-endian ARM a 0xAARRGGBB int sits in memory as B, G, R, A.
    int ok = WebPPictureImportBGRA(&picture, (const uint8_t *) pixels, width * 4);
    (*env)->ReleaseIntArrayElements(env, argb, pixels, JNI_ABORT);

    WebPMemoryWriter writer;
    WebPMemoryWriterInit(&writer);
    picture.writer = WebPMemoryWrite;
    picture.custom_ptr = &writer;
    ok = ok && WebPEncode(&config, &picture);
    WebPPictureFree(&picture);

    jbyteArray out = NULL;
    if (ok) {
        out = (*env)->NewByteArray(env, (jsize) writer.size);
        if (out != NULL) (*env)->SetByteArrayRegion(env, out, 0, (jsize) writer.size, (const jbyte *) writer.mem);
    }
    WebPMemoryWriterClear(&writer);
    return out;
}

// Encodes ARGB pixels as lossy WebP at [quality] (0..100) with sharp RGB->YUV conversion (where
// the quality gain over Android's encoder comes from: about +4 dB) and lossless transparency so
// cut edges stay exact. Measured: method 6 and "best" alpha filtering cost seconds for ~0 gain,
// so method 5 and fast alpha filtering. Returns the file bytes, or null on failure.
JNIEXPORT jbyteArray JNICALL
Java_com_example_stickermaker_editor_WebpNative_encodeLossy(
        JNIEnv *env, jclass clazz, jintArray argb, jint width, jint height, jfloat quality) {
    (void) clazz;
    WebPConfig config;
    if (!WebPConfigPreset(&config, WEBP_PRESET_DEFAULT, quality)) return NULL;
    config.method = 5;
    config.use_sharp_yuv = 1;
    config.alpha_quality = 100;
    config.alpha_compression = 1;
    config.alpha_filtering = 1;
    config.exact = 0;
    config.thread_level = 1;
    if (!WebPValidateConfig(&config)) return NULL;

    WebPPicture picture;
    if (!WebPPictureInit(&picture)) return NULL;
    picture.use_argb = 1;
    picture.width = width;
    picture.height = height;

    jint *pixels = (*env)->GetIntArrayElements(env, argb, NULL);
    if (pixels == NULL) return NULL;
    int ok = WebPPictureImportBGRA(&picture, (const uint8_t *) pixels, width * 4);
    (*env)->ReleaseIntArrayElements(env, argb, pixels, JNI_ABORT);

    WebPMemoryWriter writer;
    WebPMemoryWriterInit(&writer);
    picture.writer = WebPMemoryWrite;
    picture.custom_ptr = &writer;
    ok = ok && WebPEncode(&config, &picture);
    WebPPictureFree(&picture);

    jbyteArray out = NULL;
    if (ok) {
        out = (*env)->NewByteArray(env, (jsize) writer.size);
        if (out != NULL) (*env)->SetByteArrayRegion(env, out, 0, (jsize) writer.size, (const jbyte *) writer.mem);
    }
    WebPMemoryWriterClear(&writer);
    return out;
}
