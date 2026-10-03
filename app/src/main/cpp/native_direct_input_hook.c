#include <jni.h>
#include <android/input.h>
#include <stdint.h>

#define MAX_STROKE_POINTS 64

#pragma pack(push, 1)
typedef struct {
    float x;
    float y;
    int64_t event_time_ms;
} NativeStrokePoint;
#pragma pack(pop)

static NativeStrokePoint g_stroke_buffer[MAX_STROKE_POINTS];

JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeDirectInputHookImpl(
    JNIEnv *env, jobject thiz,
    jfloatArray j_x_coords, jfloatArray j_y_coords, 
    jfloatArray j_event_times, jint point_count,
    jfloat screen_w, jfloat screen_h,
    jfloatArray j_out_flat) {

    if (point_count > MAX_STROKE_POINTS) point_count = MAX_STROKE_POINTS;
    if (point_count <= 0) return;
    
    float *x_arr = (*env)->GetFloatArrayElements(env, j_x_coords, NULL);
    float *y_arr = (*env)->GetFloatArrayElements(env, j_y_coords, NULL);
    float *t_arr = (*env)->GetFloatArrayElements(env, j_event_times, NULL);
    
    for (int i = 0; i < point_count; i++) {
        float x = x_arr[i];
        float y = y_arr[i];
        
        x = x < 0.0f ? 0.0f : x;
        x = x > screen_w ? screen_w : x;
        
        y = y < 0.0f ? 0.0f : y;
        y = y > screen_h ? screen_h : y;
        
        g_stroke_buffer[i].x = x;
        g_stroke_buffer[i].y = y;
        g_stroke_buffer[i].event_time_ms = (int64_t)t_arr[i];
    }
    
    (*env)->ReleaseFloatArrayElements(env, j_x_coords, x_arr, JNI_ABORT);
    (*env)->ReleaseFloatArrayElements(env, j_y_coords, y_arr, JNI_ABORT);
    (*env)->ReleaseFloatArrayElements(env, j_event_times, t_arr, JNI_ABORT);
    
    float *out = (*env)->GetFloatArrayElements(env, j_out_flat, NULL);
    for (int i = 0; i < point_count; i++) {
        out[i * 3]     = g_stroke_buffer[i].x;
        out[i * 3 + 1] = g_stroke_buffer[i].y;
        out[i * 3 + 2] = (float)g_stroke_buffer[i].event_time_ms;
    }
    (*env)->ReleaseFloatArrayElements(env, j_out_flat, out, 0);
}

JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeDirectInputHookPrepare(
    JNIEnv *env, jobject thiz,
    jfloatArray j_x_coords, jfloatArray j_y_coords, 
    jfloatArray j_event_times, jint point_count,
    jfloat screen_w, jfloat screen_h,
    jfloatArray j_out_flat) {
    Java_com_assistant_NativeBridge_nativeDirectInputHookImpl(env, thiz, j_x_coords, j_y_coords, j_event_times, point_count, screen_w, screen_h, j_out_flat);
}
