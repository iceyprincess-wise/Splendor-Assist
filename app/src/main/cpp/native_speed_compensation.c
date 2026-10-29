#include <jni.h>
#include <math.h>
#include <stdint.h>

// BRANCHLESS RANGE CLIPPING: Hardware-level selection abstraction
static inline float branchless_coerce(float v, float lo, float hi) {
    float r = v;
    r = 0.5f * (r + lo + fabsf(r - lo));
    r = 0.5f * (r + hi - fabsf(hi - r));
    return r;
}

// BRANCHLESS CONDITIONAL: Replaces if/else and ternary operators
static inline float branchless_select(float cond, float A, float B) {
    return (cond * A) + ((1.0f - cond) * B);
}

