#include <jni.h>
#include <stdint.h>
#include <pthread.h>
#include <unistd.h>
#include <sys/syscall.h>
#include <performance_hint.h>

static APerformanceHintManager* g_hint_manager = NULL;
static APerformanceHintSession* g_hint_session = NULL;
static pthread_t g_worker_thread;
static volatile int32_t g_running = 0;

static void* spoofer_thread_func(void* arg) {
    int32_t tid = (int32_t)syscall(SYS_gettid);
    int32_t thread_ids[1] = { tid };
    
    if (g_hint_manager) {
        g_hint_session = APerformanceHintSession_create(
            g_hint_manager, 
            APERFORMANCE_HINT_SESSION_TYPE_MEDIA_PLAYBACK,
            thread_ids, 1, 16666666
        );
    }
    
    while (g_running) {
        if (g_hint_session) {
            APerformanceHintSession_reportWorkloadDuration(g_hint_session, 16666666);
        }
        usleep(16000);
    }
    
    if (g_hint_session) {
        APerformanceHintSession_close(g_hint_session);
        g_hint_session = NULL;
    }
    return NULL;
}

JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeStartThermalSpoofer(JNIEnv *env, jobject thiz) {
    g_hint_manager = APerformanceHintManager_getInstance();
    g_running = 1;
    pthread_create(&g_worker_thread, NULL, spoofer_thread_func, NULL);
}

JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeStopThermalSpoofer(JNIEnv *env, jobject thiz) {
    g_running = 0;
    if (g_worker_thread) {
        pthread_join(g_worker_thread, NULL);
    }
}
