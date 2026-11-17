#include "native_breadcrumb.h"
#include <jni.h>

JNIEXPORT void JNICALL Java_com_assistant_NativeBridge_nativeKickingPostureImpl(
    JNIEnv*, jclass, jfloat, jfloat, jfloat, jfloat, jfloat, jfloat, jfloat, jfloat, jfloatArray);

/* V4 (round no58): the agility method gained FOUR float params
 * (oppVx, oppVy, leadTimeSec, aggressionFactor) after oppY, so this
 * declaration carries 13 floats before the array (was 9) and the
 * RegisterNatives signature below gains 4 F's (9 -> 13 pre-array).
 * THIS IS NOT THE BOGUS FIX: the earlier draft wrongly REMOVED one F
 * from the V3 string (claiming 22 floats for a 21-float method — a
 * miscount that counted the array's own element-type F as a trailing
 * param). The V3 string was correct for V3. This change ADDS 4 F's for
 * 4 real new params. Leaving the V3 string in place would make
 * RegisterNatives fail (NoSuchMethodError) and JNI_OnLoad return
 * JNI_ERR, failing the WHOLE library load — not just agility. Proven
 * by a HotSpot replica in the no58 round: the new string registers
 * and loads with the V4 method; the old string fails with it. */
JNIEXPORT void JNICALL Java_com_assistant_NativeBridge_nativeAgilityPhysicsImpl(
    JNIEnv*, jclass, jfloat, jfloat, jfloat, jfloat, jfloat, jfloat, jfloat, jfloat, jfloat,
    jfloat, jfloat, jfloat, jfloat,
    jfloatArray, jfloat, jfloat, jfloat, jfloat, jfloat, jfloat, jfloat, jfloat, jfloat,
    jfloat, jfloat, jfloat);

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env = NULL;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK || env == NULL) {
        return JNI_ERR;
    }
#ifdef SPLENDOR_BREADCRUMBS
    splendor_native_breadcrumb("JNI_OnLoad");
    splendor_native_breadcrumb_flush(env);
#endif
    
    jclass bridge = (*env)->FindClass(env, "com/assistant/NativeBridge");
    if (bridge == NULL) return JNI_ERR;
    
    static const JNINativeMethod methods[] = {
        {"nativeKickingPostureImpl", "(FFFFFFFF[F)V", (void*)Java_com_assistant_NativeBridge_nativeKickingPostureImpl},
        {"nativeAgilityPhysicsImpl", "(FFFFFFFFFFFFF[FFFFFFFFFFFFF)V", (void*)Java_com_assistant_NativeBridge_nativeAgilityPhysicsImpl}
    };
    
    const jint rc = (*env)->RegisterNatives(env, bridge, methods, sizeof(methods) / sizeof(methods[0]));
    (*env)->DeleteLocalRef(env, bridge);
    
    return rc == 0 ? JNI_VERSION_1_6 : JNI_ERR;
}
