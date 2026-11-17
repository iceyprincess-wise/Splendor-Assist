#include "native_breadcrumb.h"
#include <android/log.h>
#include <string.h>
#include <stdio.h>
#include <pthread.h>
#include <time.h>

// In-memory breadcrumb ring. No disk I/O on the hot path; the ring is
// flushed to the log (and via the crash handler) only on demand.
#define SPLENDOR_BC_SLOTS 64
#define SPLENDOR_BC_TEXT 96

static char bc_ring[SPLENDOR_BC_SLOTS][SPLENDOR_BC_TEXT];
static pthread_mutex_t bc_lock = PTHREAD_MUTEX_INITIALIZER;
static int bc_head = 0;
static int bc_count = 0;

/* no59 ITEM 2 — grouped-log state. Own mutex (never bc_lock): the flush
 * loop below holds bc_lock while it calls the grouped logger, so sharing
 * that mutex would deadlock. Only the last location is remembered, so an
 * interleaved location re-headers — exactly the Kotlin rule. */
static pthread_mutex_t bc_group_lock = PTHREAD_MUTEX_INITIALIZER;
static char bc_group_file[64] = "";
static int bc_group_line = -1;

void splendor_log_grouped(const char *file, int line, const char *msg) {
    if (msg == NULL) return;
    /* Short location: basename only ("native_breadcrumb.c"), rendered as
     * "app/<basename>:<line>" — every .c file lives under app/src/main/cpp,
     * so the module is always "app/", matching the Kotlin side. */
    const char *base = file ? strrchr(file, '/') : NULL;
    base = base ? base + 1 : (file ? file : "?");
    pthread_mutex_lock(&bc_group_lock);
    int same = (line == bc_group_line) && (strncmp(bc_group_file, base, sizeof(bc_group_file) - 1) == 0) && (bc_group_file[0] != '\0');
    if (!same) {
        struct timespec ts;
        clock_gettime(CLOCK_REALTIME, &ts);
        struct tm tmv;
        localtime_r(&ts.tv_sec, &tmv);
        char tbuf[16];
        strftime(tbuf, sizeof(tbuf), "%H:%M:%S", &tmv);
        __android_log_print(ANDROID_LOG_INFO, "SPLENDOR_BC", "%s.%03ld: app/%s:%d:",
                            tbuf, (long)(ts.tv_nsec / 1000000), base, line);
        strncpy(bc_group_file, base, sizeof(bc_group_file) - 1);
        bc_group_file[sizeof(bc_group_file) - 1] = '\0';
        bc_group_line = line;
    }
    pthread_mutex_unlock(&bc_group_lock);
    __android_log_print(ANDROID_LOG_ERROR, "SPLENDOR_BC", "%s", msg);
}

void splendor_native_breadcrumb(const char *entry) {
    if (entry == NULL) return;
    pthread_mutex_lock(&bc_lock);
    strncpy(bc_ring[bc_head], entry, SPLENDOR_BC_TEXT - 1);
    bc_ring[bc_head][SPLENDOR_BC_TEXT - 1] = '\0';
    bc_head = (bc_head + 1) % SPLENDOR_BC_SLOTS;
    if (bc_count < SPLENDOR_BC_SLOTS) bc_count++;
    pthread_mutex_unlock(&bc_lock);
}

void splendor_native_breadcrumb_flush(JNIEnv *env) {
    (void)env;
    pthread_mutex_lock(&bc_lock);
    int start = (bc_head - bc_count + SPLENDOR_BC_SLOTS) % SPLENDOR_BC_SLOTS;
    for (int i = 0; i < bc_count; i++) {
        int idx = (start + i) % SPLENDOR_BC_SLOTS;
        /* no59 ITEM 2: every entry in one flush shares this call site, so
         * the grouped logger prints ONE header ("...: app/native_breadcrumb.c:<line>:")
         * and then the entries bare — the old code printed each entry as
         * its own bare log line with no location at all. The JNI dump
         * path below is unchanged (raw ring, no format change). */
        SPLENDOR_LOG_GROUPED(bc_ring[idx]);
    }
    pthread_mutex_unlock(&bc_lock);
}

/* JNI bridge for crash forensics: hand the ring to the JVM as one string. */
JNIEXPORT jstring JNICALL Java_com_assistant_NativeBridge_nativeBreadcrumbDump(
    JNIEnv *env, jclass clazz) {
    (void)clazz;
    char out[SPLENDOR_BC_SLOTS * (SPLENDOR_BC_TEXT + 1)];
    int len = 0;
    pthread_mutex_lock(&bc_lock);
    int start = (bc_head - bc_count + SPLENDOR_BC_SLOTS) % SPLENDOR_BC_SLOTS;
    for (int i = 0; i < bc_count && len < (int)sizeof(out) - SPLENDOR_BC_TEXT - 2; i++) {
        int idx = (start + i) % SPLENDOR_BC_SLOTS;
        len += snprintf(out + len, SPLENDOR_BC_TEXT + 2, "%s\n", bc_ring[idx]);
    }
    if (len > 0 && out[len - 1] == '\n') out[len - 1] = '\0';
    else out[len] = '\0';
    pthread_mutex_unlock(&bc_lock);
    return (*env)->NewStringUTF(env, out);
}
