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

static PlayerCoord g_players[MAX_PLAYERS];
static int32_t g_player_count = 0;

JNIEXPORT void JNICALL
Java_com_assistant_NativeBridge_nativeAnalyzeFrame(JNIEnv *env, jclass clazz,
                                                   jobject byteBuffer, jint width, jint height,
                                                   jint rowStride, jint pixelStride,
                                                   jfloatArray outBuffer) {
#ifdef SPLENDOR_BREADCRUMBS
    splendor_native_breadcrumb("Java_com_assistant_NativeBridge_nativeAnalyzeFrame");
#endif

    static int firstFrameSitesLogged = 0;
    const int logFirstFrameSites = !firstFrameSitesLogged;
    if (logFirstFrameSites) {
        firstFrameSitesLogged = 1;
        splendor_log_vision_site(env, __FILE__, __LINE__, "nativeAnalyzeFrame entry");
    }
    uint8_t* pixels = (uint8_t*) (*env)->GetDirectBufferAddress(env, byteBuffer);
    if (!pixels) {
        if (logFirstFrameSites) splendor_log_vision_site(env, __FILE__, __LINE__, "nativeAnalyzeFrame exit: null pixels");
        return;
    }

    g_player_count = 0;
    
    for (int y = 0; y < REGION_RADAR_Y && y < height; y++) {
        uint8_t* row = pixels + (y * rowStride);
        for (int x = 0; x < REGION_RADAR_X && x < width; x++) {
            uint8_t* pixel = row + (x * pixelStride);
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
