#include "native_contract.h"
#include "native_breadcrumb.h"
JNIEXPORT void JNICALL Java_com_assistant_NativeBridge_nativeDirectInputHookImpl(
    JNIEnv *env,jobject thiz,jfloatArray xs,jfloatArray ys,jfloatArray times,jint count,
    jfloat width,jfloat height,jfloatArray output) {
    (void)thiz;
    if (count>64) count=64;
    if (count<=0) return;
    if (!isfinite(width) || !isfinite(height) || width<=0 || height<=0) { splendor_reject(env,"Invalid stroke screen dimensions"); return; }
    if (!splendor_array(env,xs,count) || !splendor_array(env,ys,count) ||
        !splendor_array(env,times,count) || !splendor_array(env,output,count*3)) return;
    jfloat x[64],y[64],t[64],out[192];
    (*env)->GetFloatArrayRegion(env,xs,0,count,x); if ((*env)->ExceptionCheck(env)) return;
    (*env)->GetFloatArrayRegion(env,ys,0,count,y); if ((*env)->ExceptionCheck(env)) return;
    (*env)->GetFloatArrayRegion(env,times,0,count,t); if ((*env)->ExceptionCheck(env)) return;
    if (!splendor_finite(env,x,count) || !splendor_finite(env,y,count) || !splendor_finite(env,t,count)) return;
    for (int i=0;i<count;i++) {
        if (t[i]<0) { splendor_reject(env,"Stroke time is negative"); return; }
        out[i*3]=fminf(fmaxf(x[i],0),width); out[i*3+1]=fminf(fmaxf(y[i],0),height);
        out[i*3+2]=truncf(t[i]);
    }
    (*env)->SetFloatArrayRegion(env,output,0,count*3,out);
}
JNIEXPORT void JNICALL Java_com_assistant_NativeBridge_nativeDirectInputHookPrepare(
    JNIEnv *env,jobject thiz,jfloatArray x,jfloatArray y,jfloatArray t,jint n,
    jfloat w,jfloat h,jfloatArray out) {
    Java_com_assistant_NativeBridge_nativeDirectInputHookImpl(env,thiz,x,y,t,n,w,h,out);
}
