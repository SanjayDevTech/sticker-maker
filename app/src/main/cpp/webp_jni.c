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
