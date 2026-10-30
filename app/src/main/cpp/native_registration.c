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

JNIEXPORT void JNICALL Java_com_assistant_NativeBridge_nativeKickingPostureImpl(
    JNIEnv*, jclass, jfloat, jfloat, jfloat, jfloat, jfloat, jfloat, jfloat, jfloat, jfloatArray);

JNIEXPORT void JNICALL Java_com_assistant_NativeBridge_nativeAgilityPhysicsImpl(
    JNIEnv*, jclass, jfloat, jfloat, jfloat, jfloat, jfloat, jfloat, jfloat, jfloat, jfloat,
    jfloatArray, jfloat, jfloat, jfloat, jfloat, jfloat, jfloat, jfloat, jfloat, jfloat,
    jfloat, jfloat, jfloat);

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
#ifdef SPLENDOR_BREADCRUMBS
    splendor_native_breadcrumb("JNI_OnLoad");
#endif

    JNIEnv* env = NULL;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK || env == NULL) {
        return JNI_ERR;
    }
    
    jclass bridge = (*env)->FindClass(env, "com/assistant/NativeBridge");
    if (bridge == NULL) return JNI_ERR;
    
    static const JNINativeMethod methods[] = {
        {"nativeKickingPostureImpl", "(FFFFFFFF[F)V", (void*)Java_com_assistant_NativeBridge_nativeKickingPostureImpl},
        {"nativeAgilityPhysicsImpl", "(FFFFFFFFF[FFFFFFFFFFFFF)V", (void*)Java_com_assistant_NativeBridge_nativeAgilityPhysicsImpl}
    };
    
    const jint rc = (*env)->RegisterNatives(env, bridge, methods, sizeof(methods) / sizeof(methods[0]));
    (*env)->DeleteLocalRef(env, bridge);
    
    return rc == 0 ? JNI_VERSION_1_6 : JNI_ERR;
}
