#include "native_contract.h"
#include "native_breadcrumb.h"
JNIEXPORT void JNICALL Java_com_assistant_NativeBridge_nativePredictiveFrameInterpImpl(
    JNIEnv *env, jobject thiz, jfloatArray j_prev_x, jfloatArray j_prev_y,
    jfloatArray j_curr_x, jfloatArray j_curr_y, jfloat dt, jint player_count,
    jfloatArray j_out_x, jfloatArray j_out_y) {
    (void)thiz;
    if (player_count > 30) player_count = 30;
    if (player_count <= 0) return;
    if (!isfinite(dt) || dt <= 0) { splendor_reject(env, "Prediction dt must be positive and finite"); return; }
    if (!splendor_array(env,j_prev_x,player_count) || !splendor_array(env,j_prev_y,player_count) ||
        !splendor_array(env,j_curr_x,player_count) || !splendor_array(env,j_curr_y,player_count) ||
        !splendor_array(env,j_out_x,player_count) || !splendor_array(env,j_out_y,player_count)) return;
    jfloat px[30],py[30],cx[30],cy[30],ox[30],oy[30];
    (*env)->GetFloatArrayRegion(env,j_prev_x,0,player_count,px); if ((*env)->ExceptionCheck(env)) return;
    (*env)->GetFloatArrayRegion(env,j_prev_y,0,player_count,py); if ((*env)->ExceptionCheck(env)) return;
    (*env)->GetFloatArrayRegion(env,j_curr_x,0,player_count,cx); if ((*env)->ExceptionCheck(env)) return;
    (*env)->GetFloatArrayRegion(env,j_curr_y,0,player_count,cy); if ((*env)->ExceptionCheck(env)) return;
    if (!splendor_finite(env,px,player_count) || !splendor_finite(env,py,player_count) ||
        !splendor_finite(env,cx,player_count) || !splendor_finite(env,cy,player_count)) return;
    for (int i=0;i<player_count;i++) { ox[i]=cx[i]+(cx[i]-px[i]); oy[i]=cy[i]+(cy[i]-py[i]); }
    if (!splendor_finite(env,ox,player_count) || !splendor_finite(env,oy,player_count)) return;
    (*env)->SetFloatArrayRegion(env,j_out_x,0,player_count,ox); if ((*env)->ExceptionCheck(env)) return;
    (*env)->SetFloatArrayRegion(env,j_out_y,0,player_count,oy);
}
JNIEXPORT void JNICALL Java_com_assistant_NativeBridge_nativePredictiveFrameInterp(
    JNIEnv *env, jobject thiz, jfloatArray px,jfloatArray py,jfloatArray cx,jfloatArray cy,
    jfloat dt,jint count,jfloatArray ox,jfloatArray oy) {
    Java_com_assistant_NativeBridge_nativePredictiveFrameInterpImpl(env,thiz,px,py,cx,cy,dt,count,ox,oy);
}
