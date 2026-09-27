#include <jni.h>
#include <stdint.h>
#include <stdatomic.h>

typedef struct {
    atomic_int is_counter_attack;
    atomic_int ball_carrier_is_user;
    atomic_int threat_level;
    atomic_int user_score;
    atomic_int opp_score;
    atomic_float ball_x;
    atomic_float ball_y;
} MatchStateGrid;

static MatchStateGrid g_state_grid = {0};

JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeUpdateMatchState(JNIEnv *env, jclass clazz,
                                                       jint isCounter, jint carrierIsUser,
                                                       jint threat, jfloat ballX, jfloat ballY) {
    atomic_store_explicit(&g_state_grid.is_counter_attack, isCounter, memory_order_relaxed);
    atomic_store_explicit(&g_state_grid.ball_carrier_is_user, carrierIsUser, memory_order_relaxed);
    atomic_store_explicit(&g_state_grid.threat_level, threat, memory_order_relaxed);
    atomic_store_explicit(&g_state_grid.ball_x, ballX, memory_order_relaxed);
    atomic_store_explicit(&g_state_grid.ball_y, ballY, memory_order_relaxed);
}

JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeReadMatchState(JNIEnv *env, jclass clazz, jintArray outBuffer) {
    jint* result = (*env)->GetIntArrayElements(env, outBuffer, NULL);
    if (!result) return;
    
    result[0] = atomic_load_explicit(&g_state_grid.is_counter_attack, memory_order_relaxed);
    result[1] = atomic_load_explicit(&g_state_grid.ball_carrier_is_user, memory_order_relaxed);
    result[2] = atomic_load_explicit(&g_state_grid.threat_level, memory_order_relaxed);
    result[3] = (jint)atomic_load_explicit(&g_state_grid.ball_x, memory_order_relaxed);
    result[4] = (jint)atomic_load_explicit(&g_state_grid.ball_y, memory_order_relaxed);
    
    (*env)->ReleaseIntArrayElements(env, outBuffer, result, 0);
}
