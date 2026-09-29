#include <jni.h>
#include <sys/resource.h>
#include <unistd.h>

JNIEXPORT jint JNICALL
Java_com_assistant_NativeBridge_nativeEscalateThreadPriority(JNIEnv* env, jobject thiz) {
    // Android unrooted max priority is -19 (THREAD_PRIORITY_URGENT_AUDIO)
    // -20 requires CAP_SYS_NICE (root/system signature) and will fail with EPERM
    int result = setpriority(PRIO_PROCESS, gettid(), -19);
    return (result == 0) ? 1 : 0; // 1 = success, 0 = EPERM/failed
}
