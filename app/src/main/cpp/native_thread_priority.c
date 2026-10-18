#include <jni.h>
#include <sys/resource.h>
#include <unistd.h>

JNIEXPORT jint JNICALL
Java_com_assistant_NativeBridge_nativeEscalateThreadPriority(JNIEnv* env, jobject thiz) {
    int result = setpriority(PRIO_PROCESS, gettid(), -20);
    if (result != 0) {
        result = setpriority(PRIO_PROCESS, gettid(), -19);
    }
    return (result == 0) ? 1 : 0;
}
