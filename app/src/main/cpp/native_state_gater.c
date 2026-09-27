#include <jni.h>
#include <stdint.h>
#include <stdatomic.h>
#include <string.h>

typedef struct {
    atomic_int is_counter_attack;
    atomic_int ball_carrier_is_user;
    atomic_int threat_level;
    atomic_uint_fast32_t ball_x_bits;
    atomic_uint_fast32_t ball_y_bits;
} MatchStateGrid;

static MatchStateGrid g_state_grid = {0};

JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeUpdateMatchState(JNIEnv *env, jclass clazz,
                                                       jint isCounter, jint carrierIsUser,
                                                       jint threat, jfloat ballX, jfloat ballY) {
    atomic_store_explicit(&g_state_grid.is_counter_attack, isCounter, memory_order_relaxed);
    atomic_store_explicit(&g_state_grid.ball_carrier_is_user, carrierIsUser, memory_order_relaxed);
    atomic_store_explicit(&g_state_grid.threat_level, threat, memory_order_relaxed);
    
    uint32_t bx_bits, by_bits;
    memcpy(&bx_bits, &ballX, sizeof(uint32_t));
    memcpy(&by_bits, &ballY, sizeof(uint32_t));
    
    atomic_store_explicit(&g_state_grid.ball_x_bits, bx_bits, memory_order_relaxed);
    atomic_store_explicit(&g_state_grid.ball_y_bits, by_bits, memory_order_relaxed);
}

JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeReadMatchState(JNIEnv *env, jclass clazz, jintArray outBuffer) {
    jint* result = (*env)->GetIntArrayElements(env, outBuffer, NULL);
    if (!result) return;
    
    result[0] = atomic_load_explicit(&g_state_grid.is_counter_attack, memory_order_relaxed);
    result[1] = atomic_load_explicit(&g_state_grid.ball_carrier_is_user, memory_order_relaxed);
    result[2] = atomic_load_explicit(&g_state_grid.threat_level, memory_order_relaxed);
    
    uint32_t bx_bits = atomic_load_explicit(&g_state_grid.ball_x_bits, memory_order_relaxed);
    uint32_t by_bits = atomic_load_explicit(&g_state_grid.ball_y_bits, memory_order_relaxed);
    memcpy(&result[3], &bx_bits, sizeof(float));
    memcpy(&result[4], &by_bits, sizeof(float));
    
    (*env)->ReleaseIntArrayElements(env, outBuffer, result, 0);
}
