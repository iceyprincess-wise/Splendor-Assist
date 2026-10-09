#include <jni.h>
#include <android/log.h>
#include <android/input.h>
#include <android/looper.h>

#define LOG_TAG "NativeInputBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jboolean JNICALL
Java_com_assistant_input_NativeInputBridge_nativeInjectTap(
        JNIEnv* env, jobject /* this */, jfloat x, jfloat y) {
    // Non-root environment: physical touch injection must be routed to Accessibility Service.
    LOGI("Native Tap (Delegating to Accessibility Authority): %f, %f", x, y);
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_assistant_input_NativeInputBridge_nativeInjectSwipe(
        JNIEnv* env, jobject /* this */, jfloat startX, jfloat startY, jfloat endX, jfloat endY, jlong duration) {
    LOGI("Native Swipe (Delegating to Accessibility Authority): %f,%f -> %f,%f", startX, startY, endX, endY);
    return JNI_FALSE;
}
