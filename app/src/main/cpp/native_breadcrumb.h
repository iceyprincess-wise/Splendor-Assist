#ifndef SPLENDOR_NATIVE_BREADCRUMB_H
#define SPLENDOR_NATIVE_BREADCRUMB_H
#include <jni.h>
#define SPLENDOR_BREADCRUMB_CAPACITY 64
#define SPLENDOR_BREADCRUMB_TEXT 96
#ifdef __cplusplus
extern "C" {
#endif
void splendor_native_breadcrumb(const char *entry);
void splendor_native_breadcrumb_flush(JNIEnv *env);
/*
 * no59 ITEM 2 — GROUPED SHORT-LOCATION LOGGING, C SIDE.
 *
 * Same rule as the Kotlin side (core LogFormat): a short location
 * "app/<File>.c:<line>" is printed ONCE per location run, then the
 * message lines follow bare. A call from a different location prints a
 * new header; a repeat from the same location prints just the message;
 * an interleaved location re-headers when the first location logs again.
 * A time gap does NOT force a new header. The timestamp is wall-clock
 * HH:MM:SS.mmm (the C side has no access to RuntimeLogger's
 * yyyy-MM-dd HH:mm:ss.SSS formatter; the date is already on every
 * logcat line, so the time-of-day is the matching precision).
 *
 * The C side keeps its OWN grouping state (a static last-location in
 * native_breadcrumb.c, under its own mutex): C lines go to logcat,
 * Kotlin lines go to Splendor_Field_Logs.txt, so the two streams can
 * never share one in-memory "last location". What is shared is the
 * RULE, stated here and in LogFormat.kt — one header per location run,
 * short location, no per-line repetition.
 *
 * The location is the CALLER's __FILE__/__LINE__: use the macro, never
 * call splendor_log_grouped() directly, or every line would claim to
 * come from the helper itself. All .c files live under
 * app/src/main/cpp, so the rendered module is always "app/".
 */
void splendor_log_grouped(const char *file, int line, const char *msg);
#define SPLENDOR_LOG_GROUPED(msg) splendor_log_grouped(__FILE__, __LINE__, (msg))
#ifdef __cplusplus
}
#endif
#endif
