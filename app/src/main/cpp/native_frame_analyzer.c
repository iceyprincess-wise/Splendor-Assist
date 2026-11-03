#include "native_breadcrumb.h"
#include "native_contract.h"
#include <pthread.h>
#include <jni.h>
#include <fcntl.h>
#include <string.h>
#include <unistd.h>

#include <stdint.h>
#include <string.h>

#define MAX_PLAYERS 22
#define REGION_RADAR_X 100
#define REGION_RADAR_Y 50

/* JNI source breadcrumb bridge, used only on first-frame entry/exit. */
static void splendor_log_vision_site(JNIEnv *env, const char *file, jint line, const char *phase) {
    jclass cls = (*env)->FindClass(env, "com/assistant/CrashForensics");
    if (cls == NULL) { if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env); return; }
    jmethodID method = (*env)->GetStaticMethodID(env, cls, "nativeBreadcrumb", "(Ljava/lang/String;ILjava/lang/String;)V");
    if (method == NULL) { if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env); (*env)->DeleteLocalRef(env, cls); return; }
    jstring jfile = (*env)->NewStringUTF(env, file);
    jstring jphase = (*env)->NewStringUTF(env, phase);
    if (jfile != NULL && jphase != NULL) (*env)->CallStaticVoidMethod(env, cls, method, jfile, line, jphase);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (jfile != NULL) (*env)->DeleteLocalRef(env, jfile);
    if (jphase != NULL) (*env)->DeleteLocalRef(env, jphase);
    (*env)->DeleteLocalRef(env, cls);
}

typedef struct {
    int32_t x;
    int32_t y;
    int32_t team;
} PlayerCoord;



JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeAnalyzeFrame(JNIEnv *env, jclass clazz,
                                                   jobject byteBuffer, jint width, jint height,
                                                   jint rowStride, jint pixelStride,
                                                   jfloatArray outBuffer) {
#ifdef SPLENDOR_BREADCRUMBS
    splendor_native_breadcrumb("Java_com_assistant_NativeBridge_nativeAnalyzeFrame");
#endif

    if (!splendor_array(env, outBuffer, 67)) return;
    if (!byteBuffer || width <= 0 || height <= 0 || pixelStride < 3 || rowStride <= 0 ||
        (int64_t)rowStride < (int64_t)width * pixelStride ||
        (*env)->GetDirectBufferCapacity(env, byteBuffer) < (int64_t)(height-1)*rowStride+(int64_t)(width-1)*pixelStride+3) {
        splendor_reject(env, "Invalid frame layout or truncated direct buffer"); return;
    }
    PlayerCoord g_players[MAX_PLAYERS];
    int32_t g_player_count = 0;
    static pthread_mutex_t siteLock = PTHREAD_MUTEX_INITIALIZER;
    static int firstFrameSitesLogged = 0;
    pthread_mutex_lock(&siteLock);
    const int logFirstFrameSites = !firstFrameSitesLogged;
    firstFrameSitesLogged = 1;
    pthread_mutex_unlock(&siteLock);
    if (logFirstFrameSites) {
        splendor_log_vision_site(env, __FILE__, __LINE__, "nativeAnalyzeFrame entry");
    }
    uint8_t* pixels = (uint8_t*) (*env)->GetDirectBufferAddress(env, byteBuffer);
    if (!pixels) {
        if (logFirstFrameSites) splendor_log_vision_site(env, __FILE__, __LINE__, "nativeAnalyzeFrame exit: null pixels");
        return;
    }

    g_player_count = 0;
    
    for (int y = 0; y < REGION_RADAR_Y && y < height; y++) {
        uint8_t* row = pixels + ((int64_t)y * rowStride);
        for (int x = 0; x < REGION_RADAR_X && x < width; x++) {
            uint8_t* pixel = row + ((int64_t)x * pixelStride);
            uint8_t r = pixel[0];
            uint8_t g = pixel[1];
            uint8_t b = pixel[2];
            
            int is_player = ((g > r + 20) & (g > b + 20) & ((r + b) < 200));
            if (is_player && g_player_count < MAX_PLAYERS) {
                g_players[g_player_count].x = x;
                g_players[g_player_count].y = y;
                g_players[g_player_count].team = 1;
                g_player_count++;
            }
        }
    }

    jfloat* result = (*env)->GetFloatArrayElements(env, outBuffer, NULL);
    if (result) {
        int idx = 0;
        result[idx++] = (jfloat)g_player_count;
        for (int i = 0; i < g_player_count && idx < 67; i++) {
            result[idx++] = (jfloat)g_players[i].x;
            result[idx++] = (jfloat)g_players[i].y;
            result[idx++] = (jfloat)g_players[i].team;
        }
        (*env)->ReleaseFloatArrayElements(env, outBuffer, result, 0);
    }
    if (logFirstFrameSites) splendor_log_vision_site(env, __FILE__, __LINE__, "nativeAnalyzeFrame exit");
}
