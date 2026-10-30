#include <jni.h>
#include <fcntl.h>
#include <string.h>
#include <unistd.h>

#ifdef SPLENDOR_BREADCRUMBS
static void splendor_native_breadcrumb(const char *entry) {
    const char *path = "/data/user/0/com.assistant.overlay/files/native_breadcrumbs.txt";
    int fd = open(path, O_WRONLY | O_CREAT | O_APPEND, 0600);
    if (fd >= 0) {
        (void)write(fd, entry, strlen(entry));
        (void)write(fd, "\n", 1);
        (void)close(fd);
    }
}
#endif
#include <android/log.h>
#include <android/input.h>
#include <android/looper.h>

#define LOG_TAG "NativeInputBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jboolean JNICALL
Java_com_assistant_input_NativeInputBridge_nativeInjectTap(
        JNIEnv* env, jobject /* this */, jfloat x, jfloat y) {
#ifdef SPLENDOR_BREADCRUMBS
    splendor_native_breadcrumb("Java_com_assistant_input_NativeInputBridge_nativeInjectTap");
#endif

    // Non-root environment: physical touch injection must be routed to Accessibility Service.
    LOGI("Native Tap (Delegating to Accessibility Authority): %f, %f", x, y);
    return JNI_FALSE; 
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_assistant_input_NativeInputBridge_nativeInjectSwipe(
        JNIEnv* env, jobject /* this */, jfloat startX, jfloat startY, jfloat endX, jfloat endY, jlong duration) {
#ifdef SPLENDOR_BREADCRUMBS
    splendor_native_breadcrumb("Java_com_assistant_input_NativeInputBridge_nativeInjectSwipe");
#endif

    LOGI("Native Swipe (Delegating to Accessibility Authority): %f,%f -> %f,%f", startX, startY, endX, endY);
    return JNI_FALSE;
}
