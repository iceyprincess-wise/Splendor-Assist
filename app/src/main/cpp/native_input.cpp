#include <jni.h>
#include <android/log.h>
#include <android/input.h>
#include <android/looper.h>

#define LOG_TAG "NativeInputBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jboolean JNICALL
Java_com_assistant_input_NativeInputBridge_nativeInjectTap(
        JNIEnv* env, jobject /* this */, jfloat x, jfloat y) {
    // Native fast-path logic placeholder. 
    // In a root environment, this would write to /dev/uinput.
    // In non-root, we rely on the Java-side fallback for security, 
    // but this JNI call is still faster than pure Java reflection.
    LOGI("Native Tap: %f, %f", x, y);
    return JNI_TRUE; 
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_assistant_input_NativeInputBridge_nativeInjectSwipe(
        JNIEnv* env, jobject /* this */, jfloat startX, jfloat startY, jfloat endX, jfloat endY, jlong duration) {
    LOGI("Native Swipe: %f,%f -> %f,%f", startX, startY, endX, endY);
    return JNI_TRUE;
}
