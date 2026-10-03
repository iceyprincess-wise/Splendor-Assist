#include <jni.h>
#include <stdint.h>
#include <pthread.h>
#include <unistd.h>
#include <sys/syscall.h>
#include <dlfcn.h>

typedef void* APerformanceHintManager;
typedef void* APerformanceHintSession;
typedef int32_t APerformanceHintSessionType;
#define APERFORMANCE_HINT_SESSION_TYPE_MEDIA_PLAYBACK 0

typedef APerformanceHintManager* (*pfn_APerformanceHintManager_getInstance)();
typedef APerformanceHintSession* (*pfn_APerformanceHintSession_create)(
    APerformanceHintManager* manager,
    APerformanceHintSessionType type,
    const int32_t* threadIds,
    size_t numThreads,
    int64_t initialTargetWorkNanos);
typedef void (*pfn_APerformanceHintSession_reportWorkloadDuration)(
    APerformanceHintSession* session, int64_t actualWorkNanos);
typedef void (*pfn_APerformanceHintSession_close)(APerformanceHintSession* session);

static pfn_APerformanceHintManager_getInstance fn_getInstance = NULL;
static pfn_APerformanceHintSession_create fn_create = NULL;
static pfn_APerformanceHintSession_reportWorkloadDuration fn_report = NULL;
static pfn_APerformanceHintSession_close fn_close = NULL;

static APerformanceHintManager* g_hint_manager = NULL;
static APerformanceHintSession* g_hint_session = NULL;
static pthread_t g_worker_thread;
static volatile int32_t g_running = 0;

static void* spoofer_thread_func(void* arg) {
    int32_t tid = (int32_t)syscall(SYS_gettid);
    int32_t thread_ids[1] = { tid };
    
    if (fn_create && g_hint_manager) {
        g_hint_session = fn_create(
            g_hint_manager, 
            APERFORMANCE_HINT_SESSION_TYPE_MEDIA_PLAYBACK,
            thread_ids, 1, 16666666
        );
    }
    
    while (g_running) {
        if (fn_report && g_hint_session) {
            fn_report(g_hint_session, 16666666);
        }
        usleep(16000);
    }
    
    if (fn_close && g_hint_session) {
        fn_close(g_hint_session);
        g_hint_session = NULL;
    }
    return NULL;
}

JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeStartThermalSpooferImpl(JNIEnv *env, jobject thiz) {
    if (!fn_getInstance) {
        void* handle = dlopen("libandroid.so", RTLD_NOW);
        if (handle) {
            fn_getInstance = (pfn_APerformanceHintManager_getInstance)dlsym(handle, "APerformanceHintManager_getInstance");
            fn_create = (pfn_APerformanceHintSession_create)dlsym(handle, "APerformanceHintSession_create");
            fn_report = (pfn_APerformanceHintSession_reportWorkloadDuration)dlsym(handle, "APerformanceHintSession_reportWorkloadDuration");
            fn_close = (pfn_APerformanceHintSession_close)dlsym(handle, "APerformanceHintSession_close");
        }
    }
    
    if (fn_getInstance) {
        g_hint_manager = fn_getInstance();
        g_running = 1;
        pthread_create(&g_worker_thread, NULL, spoofer_thread_func, NULL);
    }
}

JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeStopThermalSpooferImpl(JNIEnv *env, jobject thiz) {
    g_running = 0;
    if (g_worker_thread) {
        pthread_join(g_worker_thread, NULL);
        g_worker_thread = 0;
    }
}

JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeStartThermalSpoofer(JNIEnv *env, jobject thiz) {
    Java_com_assistant_NativeBridge_nativeStartThermalSpooferImpl(env, thiz);
}

JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeStopThermalSpoofer(JNIEnv *env, jobject thiz) {
    Java_com_assistant_NativeBridge_nativeStopThermalSpooferImpl(env, thiz);
}
