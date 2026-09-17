#include <jni.h>
#include <android/log.h>
#include <stdint.h>
#include <string.h>
#include <stdlib.h>

#define LOG_TAG "VisionCoreNative"
#define MAX_BLOBS 512
#define MAX_QUEUE 262144

// STATIC GLOBAL BUFFERS: Bypasses C++ STL allocators and GWP-ASan TLS hooks entirely.
// Pre-allocated for 1080p resolution (1920x1080 = 2,073,600 pixels).
static int32_t g_grid[2073600];
static uint8_t g_visited[2073600];
static int32_t g_queue[MAX_QUEUE];

typedef struct {
    int32_t minX, minY, maxX, maxY;
    int32_t pixelCount;
    float sumR, sumG, sumB;
} Blob;

static Blob g_blobs[MAX_BLOBS];

JNIEXPORT jint JNICALL
Java_com_assistant_VisionPreprocessor_nativeScanAndExtract(
    JNIEnv* env, jobject thiz,
    jobject buffer, jint width, jint height, jint rowStride, jint pixelStride,
    jint thresholdInt, jfloatArray outBlobs, jint outCapacity) {

    if (width <= 0 || height <= 0 || rowStride < width * 4 || pixelStride != 4) return -1;
    if (width * height > 2073600) return -1;

    uint8_t* pixels = (uint8_t*) (*env)->GetDirectBufferAddress(env, buffer);
    if (!pixels) return -1;

    int size = width * height;
    memset(g_visited, 0, size * sizeof(uint8_t));

    const int WEIGHT_R = 13933;
    const int WEIGHT_G = 46871;
    const int WEIGHT_B = 4732;
    const int LUMINANCE_SHIFT = 16;

    int sampleIdx = 0;
    for (int y = 0; y < height; y++) {
        uint8_t* rowPtr = pixels + y * rowStride;
        for (int x = 0; x < width; x++) {
            int r = rowPtr[x * 4];
            int g = rowPtr[x * 4 + 1];
            int b = rowPtr[x * 4 + 2];
            int lum = (r * WEIGHT_R + g * WEIGHT_G + b * WEIGHT_B) >> LUMINANCE_SHIFT;
            
            if (lum >= thresholdInt) {
                g_grid[y * width + x] = sampleIdx;
                sampleIdx++;
            } else {
                g_grid[y * width + x] = -1;
            }
        }
    }

    const int dx[8] = {-1, 0, 1, -1, 1, -1, 0, 1};
    const int dy[8] = {-1, -1, -1, 0, 0, 1, 1, 1};

    int blobCount = 0;

    for (int gridIdx = 0; gridIdx < size; gridIdx++) {
        if (g_grid[gridIdx] != -1 && !g_visited[gridIdx]) {
            if (blobCount >= MAX_BLOBS) break;

            int startX = gridIdx % width;
            int startY = gridIdx / width;

            Blob* blob = &g_blobs[blobCount];
            blob->minX = startX; blob->maxX = startX;
            blob->minY = startY; blob->maxY = startY;
            blob->pixelCount = 0;
            blob->sumR = 0; blob->sumG = 0; blob->sumB = 0;

            int qHead = 0;
            int qTail = 0;
            
            g_queue[qTail++] = gridIdx;
            g_visited[gridIdx] = 1;

            while (qHead < qTail && qTail < MAX_QUEUE) {
                int curr = g_queue[qHead++];
                int cx = curr % width;
                int cy = curr / width;
                
                int cIdx = cy * rowStride + cx * 4;
                float cr = pixels[cIdx];
                float cg = pixels[cIdx + 1];
                float cb = pixels[cIdx + 2];

                blob->pixelCount++;
                if (cx < blob->minX) blob->minX = cx;
                if (cy < blob->minY) blob->minY = cy;
                if (cx > blob->maxX) blob->maxX = cx;
                if (cy > blob->maxY) blob->maxY = cy;
                blob->sumR += cr;
                blob->sumG += cg;
                blob->sumB += cb;

                for (int i = 0; i < 8; i++) {
                    int nx = cx + dx[i];
                    int ny = cy + dy[i];
                    if (nx >= 0 && nx < width && ny >= 0 && ny < height) {
                        int nIdx = ny * width + nx;
                        if (!g_visited[nIdx] && g_grid[nIdx] != -1) {
                            g_visited[nIdx] = 1;
                            if (qTail < MAX_QUEUE) {
                                g_queue[qTail++] = nIdx;
                            }
                        }
                    }
                }
            }
            blobCount++;
        }
    }

    if (blobCount > outCapacity) {
        return -blobCount;
    }

    jfloat* out = (*env)->GetFloatArrayElements(env, outBlobs, NULL);
    if (!out) return -1;

    for (int i = 0; i < blobCount; i++) {
        const Blob* b = &g_blobs[i];
        int base = i * 8;
        out[base + 0] = (float)b->minX;
        out[base + 1] = (float)b->minY;
        out[base + 2] = (float)b->maxX;
        out[base + 3] = (float)b->maxY;
        out[base + 4] = (float)b->pixelCount;
        out[base + 5] = b->sumR / b->pixelCount;
        out[base + 6] = b->sumG / b->pixelCount;
        out[base + 7] = b->sumB / b->pixelCount;
    }

    (*env)->ReleaseFloatArrayElements(env, outBlobs, out, 0);
    return blobCount;
}
