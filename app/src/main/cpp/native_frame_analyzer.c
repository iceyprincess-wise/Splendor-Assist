#include <jni.h>
#include <stdint.h>
#include <string.h>

#define MAX_PLAYERS 22
#define REGION_RADAR_X 100
#define REGION_RADAR_Y 50

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
    uint8_t* pixels = (uint8_t*) (*env)->GetDirectBufferAddress(env, byteBuffer);
    if (!pixels) return;

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
}
