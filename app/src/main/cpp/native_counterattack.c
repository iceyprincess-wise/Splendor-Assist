#include <jni.h>
#include <fcntl.h>
#include <string.h>
#include <unistd.h>

#ifdef SPLENDOR_BREADCRUMBS
static void splendor_native_breadcrumb(const char *entry) {
    const char *path = "/data/user/0/com.assistant.overlay/files/native_breadcrumbs.txt";
    int fd = open(path, O_WRONLY | O_CREAT | O_APPEND, 0600);
    if (fd >= 0) {
        (void)write(fd, entry, strlen(entry));
        (void)write(fd, "\n", 1);
        (void)close(fd);
    }
}
#endif
#include <stdint.h>
#include <math.h>

static inline float branchless_coerce(float value, float min_val, float max_val) {
    float r = value;
    r = 0.5f * (r + min_val + fabsf(r - min_val));
    r = 0.5f * (r + max_val - fabsf(max_val - r));
    return r;
}

JNIEXPORT void JNICALL Java_com_assistant_NativeBridge_nativeCounterattackAnalyze(
    JNIEnv* env, jobject thiz, jint attackers, jboolean teamShapeFound, jfloat teamShapeConf,
    jboolean offensiveLineFound, jfloat offensiveLineConf, jfloatArray out) {
#ifdef SPLENDOR_BREADCRUMBS
    splendor_native_breadcrumb("Java_com_assistant_NativeBridge_nativeCounterattackAnalyze");
#endif

    if ((*env)->GetArrayLength(env, out) < 2) return;
    jfloat* r = (*env)->GetPrimitiveArrayCritical(env, out, NULL);
    if (!r) return;
    
    if (!isfinite(teamShapeConf)) teamShapeConf = 0.0f;
    if (!isfinite(offensiveLineConf)) offensiveLineConf = 0.0f;
    float shapeMask = (float)teamShapeFound;
    float lineMask = (float)offensiveLineFound;
    
    float conf = (attackers * 0.090909f) + 
                 (shapeMask * 0.15f) + 
                 (lineMask * 0.15f) + 
                 (teamShapeConf * 0.05f) + 
                 (offensiveLineConf * 0.05f);
                 
    if (!isfinite(conf)) conf = 0.0f;
    conf = branchless_coerce(conf, 0.0f, 1.0f);
    
    // NO HARD GATES. Unconditional aggressive output via smoothstep amplification
    float amplifiedConf = conf * conf * (3.0f - 2.0f * conf); 
    
    r[0] = amplifiedConf; 
    r[1] = conf;
    (*env)->ReleasePrimitiveArrayCritical(env, out, r, 0);
}
