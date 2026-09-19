#include <jni.h>

JNIEXPORT void JNICALL Java_com_assistant_NativeBridge_nativeKickingPostureImpl(
    JNIEnv*, jclass, jfloat, jfloat, jfloat, jfloat, jfloat, jfloat, jfloat, jfloat, jfloatArray);

JNIEXPORT void JNICALL Java_com_assistant_NativeBridge_nativeAgilityPhysicsImpl(
    JNIEnv*, jclass, jfloat, jfloat, jfloat, jfloat, jfloat, jfloat, jfloat, jfloat, jfloat, jint, jfloatArray);

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env = NULL;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK || env == NULL) {
        return JNI_ERR;
    }
    
    jclass bridge = (*env)->FindClass(env, "com/assistant/NativeBridge");
    if (bridge == NULL) return JNI_ERR;
    
    static const JNINativeMethod methods[] = {
        {"nativeKickingPostureImpl", "(FFFFFFFF[F)V", (void*)Java_com_assistant_NativeBridge_nativeKickingPostureImpl},
        {"nativeAgilityPhysicsImpl", "(FFFFFFFFFI[F)V", (void*)Java_com_assistant_NativeBridge_nativeAgilityPhysicsImpl}
    };
    
    const jint rc = (*env)->RegisterNatives(env, bridge, methods, sizeof(methods) / sizeof(methods[0]));
    (*env)->DeleteLocalRef(env, bridge);
    
    return rc == 0 ? JNI_VERSION_1_6 : JNI_ERR;
}
