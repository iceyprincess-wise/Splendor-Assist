#include <jni.h>
#include <stdint.h>
#include <math.h>

#define MAX_PLAYERS 30

#pragma pack(push, 1)
typedef struct {
    float x;
    float y;
    float vx;
    float vy;
} PlayerState;
#pragma pack(pop)

static PlayerState g_states[MAX_PLAYERS];

JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativePredictiveFrameInterpImpl(
    JNIEnv *env, jobject thiz,
    jfloatArray j_prev_x, jfloatArray j_prev_y,
    jfloatArray j_curr_x, jfloatArray j_curr_y,
    jfloat dt, jint player_count,
    jfloatArray j_out_x, jfloatArray j_out_y) {
    
    if (player_count > MAX_PLAYERS) player_count = MAX_PLAYERS;
    if (player_count <= 0) return;
    
    float *px = (*env)->GetFloatArrayElements(env, j_prev_x, NULL);
    float *py = (*env)->GetFloatArrayElements(env, j_prev_y, NULL);
    float *cx = (*env)->GetFloatArrayElements(env, j_curr_x, NULL);
    float *cy = (*env)->GetFloatArrayElements(env, j_curr_y, NULL);
    
    float *out_x = (*env)->GetFloatArrayElements(env, j_out_x, NULL);
    float *out_y = (*env)->GetFloatArrayElements(env, j_out_y, NULL);
    
    for (int i = 0; i < player_count; i++) {
        float vx = (cx[i] - px[i]) / dt;
        float vy = (cy[i] - py[i]) / dt;
        
        float pred_x = cx[i] + vx * dt;
        float pred_y = cy[i] + vy * dt;
        
        out_x[i] = pred_x;
        out_y[i] = pred_y;
        
        g_states[i].x = cx[i];
        g_states[i].y = cy[i];
        g_states[i].vx = vx;
        g_states[i].vy = vy;
    }
    
    (*env)->ReleaseFloatArrayElements(env, j_prev_x, px, JNI_ABORT);
    (*env)->ReleaseFloatArrayElements(env, j_prev_y, py, JNI_ABORT);
    (*env)->ReleaseFloatArrayElements(env, j_curr_x, cx, JNI_ABORT);
    (*env)->ReleaseFloatArrayElements(env, j_curr_y, cy, JNI_ABORT);
    
    (*env)->ReleaseFloatArrayElements(env, j_out_x, out_x, 0);
    (*env)->ReleaseFloatArrayElements(env, j_out_y, out_y, 0);
}

JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativePredictiveFrameInterp(
    JNIEnv *env, jobject thiz,
    jfloatArray j_prev_x, jfloatArray j_prev_y,
    jfloatArray j_curr_x, jfloatArray j_curr_y,
    jfloat dt, jint player_count,
    jfloatArray j_out_x, jfloatArray j_out_y) {
    Java_com_assistant_NativeBridge_nativePredictiveFrameInterpImpl(env, thiz, j_prev_x, j_prev_y, j_curr_x, j_curr_y, dt, player_count, j_out_x, j_out_y);
}
