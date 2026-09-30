#include <jni.h>
#include <sys/resource.h>
#include <unistd.h>
#include <pthread.h>
#include <sched.h>

JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeEscalateThreadPriority(JNIEnv* env, jclass clazz) {
    pid_t tid = gettid();
    int ret = setpriority(PRIO_PROCESS, tid, -20);
    if (ret == -1) {
        ret = setpriority(PRIO_PROCESS, tid, -10);
    }
    struct sched_param param;
    param.sched_priority = sched_get_priority_max(SCHED_FIFO);
    pthread_setschedparam(pthread_self(), SCHED_FIFO, &param);
}
