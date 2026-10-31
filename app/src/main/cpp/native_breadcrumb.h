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
#ifdef __cplusplus
}
#endif
#endif
