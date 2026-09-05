#include <jni.h>
#include <android/log.h>
#include <vector>
#include <cstdint>
#include <cstring>

#define LOG_TAG "VisionPreprocessor"

struct Blob {
    int32_t minX, minY, maxX, maxY;
    int32_t pixelCount;
    float sumR, sumG, sumB;
};

struct Workspace {
    std::vector<int32_t> grid;
    std::vector<uint8_t> visited;
    std::vector<int32_t> queue;
    std::vector<Blob> blobs;
    
    void ensureCapacity(int size) {
        if ((int)grid.size() < size) {
            grid.resize(size);
            visited.resize(size);
        }
    }
};

static Workspace g_ws;

extern "C" JNIEXPORT jint JNICALL
Java_com_assistant_adapter_smartassist_VisionPreprocessor_processFrameNative(
    JNIEnv* env, jobject thiz,
    jobject buffer, jint width, jint height, jint rowStride, jint pixelStride,
    jint thresholdInt, jint adaptiveNoiseVariance, jfloat serverTickSyncScale,
    jfloatArray outBlobs, jint outCapacity) {

    if (adaptiveNoiseVariance != 0 || serverTickSyncScale != 1.0f) return -1;
    if (pixelStride != 4 || rowStride != width * 4) return -1;
    if (width <= 0 || height <= 0) return -1;

    uint8_t* pixels = (uint8_t*) env->GetDirectBufferAddress(buffer);
    if (!pixels) return -1;

    int size = width * height;
    g_ws.ensureCapacity(size);
    std::fill_n(g_ws.grid.data(), size, -1);
    std::fill_n(g_ws.visited.data(), size, 0);
    g_ws.queue.clear();
    g_ws.blobs.clear();

    const int WEIGHT_R = 13933;
    const int WEIGHT_G = 46871;
    const int WEIGHT_B = 4732;
    const int LUMINANCE_SHIFT = 16;

    int index = 0;
    int sampleIdx = 0;
    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            int r = pixels[index];
            int g = pixels[index + 1];
            int b = pixels[index + 2];
            int lum = (r * WEIGHT_R + g * WEIGHT_G + b * WEIGHT_B) >> LUMINANCE_SHIFT;
            
            if (lum >= thresholdInt) {
                g_ws.grid[y * width + x] = sampleIdx;
                sampleIdx++;
            }
            index += 4;
        }
    }

    const int dx[8] = {-1, 0, 1, -1, 1, -1, 0, 1};
    const int dy[8] = {-1, -1, -1, 0, 0, 1, 1, 1};

    int globalSampleIdx = 0;
    index = 0;
    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            int r = pixels[index];
            int g = pixels[index + 1];
            int b = pixels[index + 2];
            int lum = (r * WEIGHT_R + g * WEIGHT_G + b * WEIGHT_B) >> LUMINANCE_SHIFT;
            
            if (lum >= thresholdInt) {
                int gridIdx = y * width + x;
                if (!g_ws.visited[gridIdx] && g_ws.grid[gridIdx] == globalSampleIdx) {
                    Blob blob;
                    blob.minX = x; blob.maxX = x;
                    blob.minY = y; blob.maxY = y;
                    blob.pixelCount = 0;
                    blob.sumR = 0; blob.sumG = 0; blob.sumB = 0;

                    g_ws.queue.push_back(gridIdx);
                    g_ws.visited[gridIdx] = 1;

                    size_t qHead = 0;
                    while (qHead < g_ws.queue.size()) {
                        int curr = g_ws.queue[qHead++];
                        int cx = curr % width;
                        int cy = curr / width;
                        
                        int cIdx = (cy * width + cx) * 4;
                        float cr = pixels[cIdx];
                        float cg = pixels[cIdx + 1];
                        float cb = pixels[cIdx + 2];

                        blob.pixelCount++;
                        if (cx < blob.minX) blob.minX = cx;
                        if (cy < blob.minY) blob.minY = cy;
                        if (cx > blob.maxX) blob.maxX = cx;
                        if (cy > blob.maxY) blob.maxY = cy;
                        blob.sumR += cr;
                        blob.sumG += cg;
                        blob.sumB += cb;

                        for (int i = 0; i < 8; i++) {
                            int nx = cx + dx[i];
                            int ny = cy + dy[i];
                            if (nx >= 0 && nx < width && ny >= 0 && ny < height) {
                                int nIdx = ny * width + nx;
                                if (!g_ws.visited[nIdx] && g_ws.grid[nIdx] != -1) {
                                    g_ws.visited[nIdx] = 1;
                                    g_ws.queue.push_back(nIdx);
                                }
                            }
                        }
                    }
                    g_ws.blobs.push_back(blob);
                }
                globalSampleIdx++;
            }
            index += 4;
        }
    }

    int blobCount = g_ws.blobs.size();
    if (blobCount > outCapacity) {
        return -blobCount;
    }

    jfloat* out = env->GetFloatArrayElements(outBlobs, NULL);
    if (!out) return -1;

    for (int i = 0; i < blobCount; i++) {
        const Blob& b = g_ws.blobs[i];
        int base = i * 8;
        out[base + 0] = (float)b.minX;
        out[base + 1] = (float)b.minY;
        out[base + 2] = (float)b.maxX;
        out[base + 3] = (float)b.maxY;
        out[base + 4] = (float)b.pixelCount;
        out[base + 5] = b.sumR / b.pixelCount;
        out[base + 6] = b.sumG / b.pixelCount;
        out[base + 7] = b.sumB / b.pixelCount;
    }

    env->ReleaseFloatArrayElements(outBlobs, out, 0);
    return blobCount;
}
