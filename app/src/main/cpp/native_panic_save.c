#include <jni.h>
#include <stdint.h>

// PanicAction ordinals: 0=HOLD, 1=BLOCK_LEFT, 2=BLOCK_RIGHT, 3=RUSH
// ShotDirection ordinals: 0=NEAR_POST, 1=FAR_POST, 2=CENTER, 3=CROSS, 4=LONG_BALL

JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativePanicSaveCompute(
        JNIEnv* env, jobject thiz,
        jint directionOrdinal, jint priority,
        jfloatArray outBuffer) {
            
    jfloat* result = (jfloat*)(*env)->GetPrimitiveArrayCritical(env, outBuffer, NULL);
    if (!result) return;

    int actionOrdinal = 0; // HOLD
    if (priority >= 130) {
        if (directionOrdinal == 0) actionOrdinal = 2; // NEAR_POST -> BLOCK_RIGHT
        else if (directionOrdinal == 1) actionOrdinal = 1; // FAR_POST -> BLOCK_LEFT
        else actionOrdinal = 3; // RUSH
    }

    result[0] = (float)actionOrdinal;
    (*env)->ReleasePrimitiveArrayCritical(env, outBuffer, result, 0);
}
