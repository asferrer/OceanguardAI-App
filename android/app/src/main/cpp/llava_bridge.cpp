#include <jni.h>
#include <android/log.h>
#include <string>

#include "llama.h"

#define TAG "LlavaBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ---------------------------------------------------------------------------
// Vision bridge — stub mode.
//
// llama.cpp b8583 migrated vision from examples/llava/ to tools/mtmd/ with a
// new API that requires ~20 model backends + audio dependencies. OceanGuard
// uses Qwen3.5-2B text-only for report generation — vision is out of scope.
//
// All JNI functions return safe no-ops so Kotlin callers can detect the stub
// via nativeIsVisionSupported() and skip vision initialization gracefully.
// ---------------------------------------------------------------------------

extern "C" JNIEXPORT jboolean JNICALL
Java_com_oceanguard_ai_inference_LlamaCppBridge_nativeIsVisionSupported(
        JNIEnv* /* env */, jobject /* thiz */) {
    LOGI("nativeIsVisionSupported: false (stub build)");
    return JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_oceanguard_ai_inference_LlamaCppBridge_nativeInitClipModel(
        JNIEnv* /* env */, jobject /* thiz */,
        jlong /* llmContextHandle */, jstring /* mmprojPath */, jint /* nThreads */) {
    LOGE("nativeInitClipModel: vision not compiled (stub build)");
    return 0L;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_oceanguard_ai_inference_LlamaCppBridge_nativeGenerateWithImage(
        JNIEnv* env, jobject /* thiz */,
        jlong /* llmContextHandle */, jlong /* clipContextHandle */,
        jintArray /* bitmapPixels */, jint /* bitmapWidth */, jint /* bitmapHeight */,
        jstring /* prompt */, jint /* maxTokens */, jfloat /* temperature */,
        jobject /* callback */) {
    LOGE("nativeGenerateWithImage: vision not compiled (stub build)");
    return env->NewStringUTF("");
}

extern "C" JNIEXPORT void JNICALL
Java_com_oceanguard_ai_inference_LlamaCppBridge_nativeReleaseClipModel(
        JNIEnv* /* env */, jobject /* thiz */, jlong /* clipContextHandle */) {
    // no-op
}
