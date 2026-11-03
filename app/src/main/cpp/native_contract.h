/* Shared JNI boundary validation. Invalid input is not gameplay telemetry. */
#ifndef SPLENDOR_NATIVE_CONTRACT_H
#define SPLENDOR_NATIVE_CONTRACT_H
#include <jni.h>
#include <math.h>
#include <stddef.h>
static inline int splendor_reject(JNIEnv *env, const char *message) {
    if (env && !(*env)->ExceptionCheck(env)) {
        jclass cls = (*env)->FindClass(env, "java/lang/IllegalArgumentException");
        if (cls) { (*env)->ThrowNew(env, cls, message); (*env)->DeleteLocalRef(env, cls); }
    }
    return 0;
}
static inline int splendor_array(JNIEnv *env, jarray array, jsize size) {
    if (!env || !array || (*env)->GetArrayLength(env, array) < size)
        return splendor_reject(env, "JNI array is null or shorter than its declared output/input contract");
    return 1;
}
static inline int splendor_finite(JNIEnv *env, const jfloat *values, size_t count) {
    for (size_t i=0; i<count; ++i) if (!isfinite(values[i]))
        return splendor_reject(env, "JNI coordinate or model input is not finite");
    return 1;
}
#endif
