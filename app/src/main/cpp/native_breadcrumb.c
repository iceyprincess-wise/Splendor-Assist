#include "native_breadcrumb.h"
#include <android/log.h>
#include <string.h>

// In-memory breadcrumb ring. No disk I/O on the hot path; the ring is
// flushed to the log (and via the crash handler) only on demand.
#define SPLENDOR_BC_SLOTS 64
#define SPLENDOR_BC_TEXT 96

static char bc_ring[SPLENDOR_BC_SLOTS][SPLENDOR_BC_TEXT];
static int bc_head = 0;
static int bc_count = 0;

void splendor_native_breadcrumb(const char *entry) {
    if (entry == NULL) return;
    strncpy(bc_ring[bc_head], entry, SPLENDOR_BC_TEXT - 1);
    bc_ring[bc_head][SPLENDOR_BC_TEXT - 1] = '\0';
    bc_head = (bc_head + 1) % SPLENDOR_BC_SLOTS;
    if (bc_count < SPLENDOR_BC_SLOTS) bc_count++;
}

void splendor_native_breadcrumb_flush(JNIEnv *env) {
    (void)env;
    int start = (bc_head - bc_count + SPLENDOR_BC_SLOTS) % SPLENDOR_BC_SLOTS;
    for (int i = 0; i < bc_count; i++) {
        int idx = (start + i) % SPLENDOR_BC_SLOTS;
        __android_log_print(ANDROID_LOG_ERROR, "SPLENDOR_BC", "%s", bc_ring[idx]);
    }
}
