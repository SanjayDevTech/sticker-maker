// Bridge from Kotlin (editor/WebpNative.kt) to libwebp: still (lossless/lossy) and animated stickers.
#include <jni.h>
#include <stdint.h>
#include <string.h>
#include "webp/encode.h"
#include "webp/mux.h"

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
// so method 5 and fast alpha filtering ([method] is lower for animation frames, which pay it
// many times). Returns the file bytes, or null on failure.
JNIEXPORT jbyteArray JNICALL
Java_com_example_stickermaker_editor_WebpNative_encodeLossy(
        JNIEnv *env, jclass clazz, jintArray argb, jint width, jint height, jfloat quality, jint method) {
    (void) clazz;
    WebPConfig config;
    if (!WebPConfigPreset(&config, WEBP_PRESET_DEFAULT, quality)) return NULL;
    config.method = method;
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

// ---- Animated stickers ------------------------------------------------------------------------
// Each frame is encoded on its own (as a full still, in parallel on the Kotlin side) and then
// stitched here. libwebp's WebPAnimEncoder tries 4-8 encodes per frame to find changed
// rectangles, which buys nothing when the whole sticker moves every frame: measured 7-23 s
// per sticker on a Pixel 9, against well under a second this way.

// Joins still WebP files into one looping animation, frame i shown for durations[i] ms and fully
// replacing the last (no blending, so moving transparent edges leave no trails). Returns the
// file bytes, or null on failure.
JNIEXPORT jbyteArray JNICALL
Java_com_example_stickermaker_editor_WebpNative_assemble(
        JNIEnv *env, jclass clazz, jobjectArray frames, jintArray durations) {
    (void) clazz;
    jsize count = (*env)->GetArrayLength(env, frames);
    if ((*env)->GetArrayLength(env, durations) != count) return NULL;
    jint *ms = (*env)->GetIntArrayElements(env, durations, NULL);
    if (ms == NULL) return NULL;
    WebPMux *mux = WebPMuxNew();
    int ok = mux != NULL;
    for (jsize i = 0; ok && i < count; i++) {
        jbyteArray frame = (jbyteArray) (*env)->GetObjectArrayElement(env, frames, i);
        jsize size = (*env)->GetArrayLength(env, frame);
        jbyte *bytes = (*env)->GetByteArrayElements(env, frame, NULL);
        if (bytes == NULL) {
            ok = 0;
        } else {
            WebPMuxFrameInfo info;
            memset(&info, 0, sizeof(info));
            info.bitstream.bytes = (const uint8_t *) bytes;
            info.bitstream.size = (size_t) size;
            info.id = WEBP_CHUNK_ANMF;
            info.duration = ms[i];
            info.dispose_method = WEBP_MUX_DISPOSE_NONE;
            info.blend_method = WEBP_MUX_NO_BLEND;
            ok = WebPMuxPushFrame(mux, &info, 1) == WEBP_MUX_OK; // copies the data
            (*env)->ReleaseByteArrayElements(env, frame, bytes, JNI_ABORT);
        }
        (*env)->DeleteLocalRef(env, frame);
    }
    (*env)->ReleaseIntArrayElements(env, durations, ms, JNI_ABORT);
    if (mux == NULL) return NULL;
    WebPMuxAnimParams params;
    params.bgcolor = 0x00000000; // transparent
    params.loop_count = 0; // forever
    ok = ok && WebPMuxSetAnimationParams(mux, &params) == WEBP_MUX_OK;
    WebPData data;
    WebPDataInit(&data);
    ok = ok && WebPMuxAssemble(mux, &data) == WEBP_MUX_OK;
    WebPMuxDelete(mux);
    jbyteArray out = NULL;
    if (ok) {
        out = (*env)->NewByteArray(env, (jsize) data.size);
        if (out != NULL) (*env)->SetByteArrayRegion(env, out, 0, (jsize) data.size, (const jbyte *) data.bytes);
    }
    WebPDataClear(&data);
    return out;
}
