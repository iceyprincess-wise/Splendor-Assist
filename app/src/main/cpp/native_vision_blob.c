#include <jni.h>
#include <stdint.h>
#include <string.h>

#define MAX_PIXELS 1048576
#define MAX_LABELS 200000

static int g_parent[MAX_LABELS];
static int g_minX[MAX_LABELS];
static int g_minY[MAX_LABELS];
static int g_maxX[MAX_LABELS];
static int g_maxY[MAX_LABELS];
static int g_count[MAX_LABELS];
static int g_sumR[MAX_LABELS];
static int g_sumG[MAX_LABELS];
static int g_sumB[MAX_LABELS];
static int g_labels[MAX_PIXELS];

static inline int find_root(int i) {
    int root = i;
    while (g_parent[root] != root) {
        root = g_parent[root];
    }
    int curr = i;
    while (curr != root) {
        int next = g_parent[curr];
        g_parent[curr] = root;
        curr = next;
    }
    return root;
}

static inline void union_labels(int a, int b) {
    int rootA = find_root(a);
    int rootB = find_root(b);
    if (rootA != rootB) {
        if (rootA < rootB) {
            g_parent[rootB] = rootA;
        } else {
            g_parent[rootA] = rootB;
        }
    }
}

JNIEXPORT jint JNICALL
Java_com_assistant_NativeBridge_nativeExtractBlobs(
    JNIEnv *env,
    jclass clazz,
    jobject byteBuffer,
    jint width,
    jint height,
    jint rowStride,
    jint pixelStride,
    jfloat threshold,
    jintArray outputBlobs
) {
    (void)clazz;
    uint8_t* pixels = (uint8_t*) (*env)->GetDirectBufferAddress(env, byteBuffer);
    if (!pixels) return -1;

    jint* out = (*env)->GetPrimitiveArrayCritical(env, outputBlobs, NULL);
    if (!out) return -1;

    int thresholdInt = (int)(threshold * 255.0f);
    int nextLabel = 1;
    
    if (width * height > MAX_PIXELS) {
        (*env)->ReleasePrimitiveArrayCritical(env, outputBlobs, out, 0);
        return -1;
    }

    // Pass 1: Assign labels
    for (int y = 0; y < height; y++) {
        const uint8_t* row = pixels + y * rowStride;
        for (int x = 0; x < width; x++) {
            const uint8_t* p = row + x * pixelStride;
            int r = p[0];
            int g = p[1];
            int b = p[2];
            
            int lum = (r * 13933 + g * 46871 + b * 4732) >> 16;
            int idx = y * width + x;
            
            if (lum >= thresholdInt) {
                int label = 0;
                int leftLabel = 0;
                int topLabel = 0;
                
                if (x > 0) leftLabel = g_labels[idx - 1];
                if (y > 0) topLabel = g_labels[idx - width];
                
                if (leftLabel == 0 && topLabel == 0) {
                    if (nextLabel < MAX_LABELS) {
                        label = nextLabel++;
                        g_parent[label] = label;
                        g_minX[label] = x; g_maxX[label] = x;
                        g_minY[label] = y; g_maxY[label] = y;
                        g_count[label] = 0;
                        g_sumR[label] = 0; g_sumG[label] = 0; g_sumB[label] = 0;
                    }
                } else if (leftLabel != 0 && topLabel == 0) {
                    label = leftLabel;
                } else if (leftLabel == 0 && topLabel != 0) {
                    label = topLabel;
                } else {
                    label = leftLabel < topLabel ? leftLabel : topLabel;
                    if (leftLabel != topLabel) {
                        union_labels(leftLabel, topLabel);
                    }
                }
                g_labels[idx] = label;
            } else {
                g_labels[idx] = 0;
            }
        }
    }
    
    // Pass 2: Flatten and aggregate
    for (int y = 0; y < height; y++) {
        const uint8_t* row = pixels + y * rowStride;
        for (int x = 0; x < width; x++) {
            int idx = y * width + x;
            int label = g_labels[idx];
            if (label != 0) {
                int root = find_root(label);
                if (x < g_minX[root]) g_minX[root] = x;
                if (x > g_maxX[root]) g_maxX[root] = x;
                if (y < g_minY[root]) g_minY[root] = y;
                if (y > g_maxY[root]) g_maxY[root] = y;
                g_count[root]++;
                
                const uint8_t* p = row + x * pixelStride;
                g_sumR[root] += p[0];
                g_sumG[root] += p[1];
                g_sumB[root] += p[2];
            }
        }
    }
    
    // Pass 3: Extract unique roots to output buffer
    int blobCount = 0;
    int maxBlobs = (*env)->GetArrayLength(env, outputBlobs) / 8;
    
    for (int i = 1; i < nextLabel; i++) {
        if (g_parent[i] == i && g_count[i] > 0) {
            if (blobCount < maxBlobs) {
                int offset = blobCount * 8;
                out[offset + 0] = g_minX[i];
                out[offset + 1] = g_minY[i];
                out[offset + 2] = g_maxX[i];
                out[offset + 3] = g_maxY[i];
                out[offset + 4] = g_count[i];
                out[offset + 5] = g_sumR[i];
                out[offset + 6] = g_sumG[i];
                out[offset + 7] = g_sumB[i];
                blobCount++;
            } else {
                break;
            }
        }
    }
    
    (*env)->ReleasePrimitiveArrayCritical(env, outputBlobs, out, 0);
    return blobCount;
}
