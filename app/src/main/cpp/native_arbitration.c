#include <jni.h>
#include <stdint.h>

static inline float splendor_coerce_in(float v, float lo, float hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

static inline uint32_t splendor_float_bits(float v) {
    union {
        float f;
        uint32_t u;
    } c;
    c.f = v;
    return c.u;
}

JNIEXPORT jlong JNICALL
Java_com_assistant_NativeBridge_nativeAuthorityArbitrate(
    JNIEnv *env,
    jclass clazz,
    jint mode,
    jfloat passX,
    jfloat passY,
    jfloat crossX,
    jfloat crossY,
    jfloat predictiveX,
    jfloat predictiveY,
    jfloat receiver,
    jfloat forward,
    jfloat recovery,
    jfloat shot,
    jfloat stability
) {
    (void)env;
    (void)clazz;

    float rx;
    float ry;

    switch ((int)mode) {
        case 1: {
            float dx = splendor_coerce_in(
                (receiver * 64.0f) + (shot * 36.0f),
                -120.0f,
                120.0f
            );
            float dy = splendor_coerce_in(
                (forward * 48.0f) + (stability * 8.0f),
                -180.0f,
                180.0f
            );
            rx = passX + dx;
            ry = passY + dy;
            break;
        }

        case 2: {
            float dx = splendor_coerce_in(
                (shot * 50.0f) + (receiver * 64.0f),
                -120.0f,
                120.0f
            );
            float dy = splendor_coerce_in(
                (recovery * 60.0f) + (stability * 8.0f),
                -180.0f,
                180.0f
            );
            rx = predictiveX + dx;
            ry = predictiveY + dy;
            break;
        }

        default: {
            float dx = splendor_coerce_in(
                receiver * 64.0f,
                -120.0f,
                120.0f
            );
            float dy = splendor_coerce_in(
                (forward * 36.0f) + (stability * 8.0f),
                -180.0f,
                180.0f
            );
            rx = crossX + dx;
            ry = crossY + dy;
            break;
        }
    }

    union {
        uint64_t u;
        int64_t i;
    } packed;

    packed.u =
        ((uint64_t)splendor_float_bits(rx) << 32) |
        (uint64_t)splendor_float_bits(ry);

    return (jlong)packed.i;
}
