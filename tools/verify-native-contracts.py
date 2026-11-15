#!/usr/bin/env python3
"""Proof for the NativeBridge JNI contracts (tools/verify-native-contracts.py).

Coverage: all 26 NativeBridge methods.
- 20 runtime-tested from 16 .c files: 15 in `lengths` (valid + null +
  short-array + NaN rejection) plus the PredictiveFrameInterp /
  DirectInputHook / OpticalVariance / PreprocessFrame / AnalyzeFrame
  special cases and the 2-thread race test.
- 5 valid-input-only (nativeAuthorityArbitrate,
  nativeBallRetentionShieldCompute, nativeInstantInterceptCompute,
  nativeBuildUpPressCompute, nativeExtractBlobs): they validate by silent
  return / -1, not by splendor_reject, so the bad() tests do not apply.
  That silent-failure gap is a C-side defect for Task 5, not a proof gap.
- 1 static-only (nativeBreadcrumbDump): native_breadcrumb.c includes
  <android/log.h> and cannot compile on a Linux host, so its C signature
  is checked against the Kotlin declaration instead of being called.
- 2 input-bridge methods (nativeInjectTap/Swipe, native_input.cpp) are
  also static-only: they need the NDK and delegate to AccessibilityService.
Run: JAVA_HOME=<jdk> python3 tools/verify-native-contracts.py
"""
from pathlib import Path
import re, subprocess, os, tempfile, shutil
r = Path(__file__).resolve().parents[1]
scratch = tempfile.TemporaryDirectory(prefix='splendor-jni-')
t = Path(scratch.name)
files = ['native_agility_physics.c', 'native_critical_vector.c', 'native_kicking_posture.c', 'native_dominance_matrix.c', 'native_stopper_matrix.c', 'native_true_shot.c', 'native_sa_corrector.c', 'native_tactical_engines.c', 'native_direct_input_hook.c', 'native_predictive_frame_interp.c', 'native_optical_variance.c', 'native_vision_preprocessor.c', 'native_frame_analyzer.c', 'native_arbitration.c', 'native_gameplay_compute.c', 'native_vision_blob.c']
methods = []
for file in files:
    s = (r / 'app/src/main/cpp' / file).read_text()
    # [^;] so a prototype declaration can never span forward into the next
    # function's '{' (native_registration.c proved the old (.*?) did).
    for m in re.finditer(r'JNIEXPORT\s+(\w+)\s+JNICALL\s+Java_com_assistant_NativeBridge_(\w+)\s*\(([^;]*?)\)\s*\{', s, re.S):
        ret, name, ps = m.groups()
        params = [p.strip().split() for p in ps.split(',')][2:]
        methods.append((name, ret, params))
maptype = {'jfloat': 'float', 'jboolean': 'boolean', 'jfloatArray': 'float[]', 'jint': 'int', 'jintArray': 'int[]', 'jlong': 'long', 'jstring': 'String', 'jobject': 'java.nio.ByteBuffer'}
retmap = {'void': 'void', 'jfloat': 'float', 'jint': 'int', 'jlong': 'long', 'jstring': 'String', 'jboolean': 'boolean'}
text = 'package com.assistant;\npublic class NativeBridge { static { System.load(System.getProperty("proof.lib")); }\n'
for name, ret, ps in methods:
    text += 'static native ' + retmap[ret] + ' ' + name + '(' + ','.join(maptype[typ] + ' ' + n for typ, n in ps) + ');\n'
text += 'static int checks=0; static void require(boolean b){if(!b)throw new AssertionError("check "+checks); checks++;}\nstatic void bad(Runnable f){try{f.run();throw new AssertionError("invalid JNI contract accepted");}catch(IllegalArgumentException expected){checks++;}}\npublic static void main(String[] args) throws Exception {\n'
lengths = {'nativeAgilityPhysicsImpl': 6, 'nativeCriticalScoringVector': 2, 'nativeCriticalTrueTargetPass': 2, 'nativeKickingPostureImpl': 4, 'nativeComputeDominanceVectors': 4, 'nativeExecuteStopperMatrix': 4, 'nativeTrueShotCompute': 5, 'nativeSACorrectPass': 3, 'nativeSACorrectShot': 4, 'nativeSACorrectCross': 4, 'nativeSACorrectKeeper': 3, 'nativeTacticalAnalytics': 1, 'nativeTacticalBehavior': 1, 'nativeRuntimeConfidenceCalibration': 1, 'nativeDefensiveCompactness': 4}
# Drift guard: every lengths entry must equal the C code's own
# splendor_array requirement for that method's output buffer.
for name, size in lengths.items():
    found = None
    for file in files:
        s = (r / 'app/src/main/cpp' / file).read_text()
        i = s.find('Java_com_assistant_NativeBridge_' + name)
        if i >= 0:
            m = re.search(r'splendor_array\(env,\s*\w+,\s*(\d+)\)', s[i:i + 4000])
            found = int(m.group(1)) if m else None
            break
    assert found == size, 'lengths drift: %s says %s, C requires %s' % (name, size, found)
for name, ret, ps in methods:
    if name not in lengths:
        continue
    size = lengths[name]

    def args(arr='out', nan=False):
        a = ['100f' if typ == 'jfloat' else 'true' if typ == 'jboolean' else '1' if typ == 'jint' else arr for typ, n in ps]
        if nan:
            a[next(i for i, (typ, n) in enumerate(ps) if (n == 'nearUnits' if name == 'nativeAgilityPhysicsImpl' else typ == 'jfloat'))] = 'Float.NaN'
        return ','.join(a)
    text += '{float[] out=new float[' + str(size) + '];' + name + '(' + args() + ');for(float v:out)require(Float.isFinite(v));\n'
    text += 'bad(()->' + name + '(' + args('null') + '));bad(()->' + name + '(' + args('new float[' + str(size - 1) + ']') + '));bad(()->' + name + '(' + args(nan=True) + '));}\n'
text += '''float[] one={2}, prev={1}, ox={-1}, oy={-1};
nativePredictiveFrameInterpImpl(prev,prev,one,one,.05f,1,ox,oy);require(ox[0]==3&&oy[0]==3);
bad(()->nativePredictiveFrameInterpImpl(prev,prev,one,one,0,1,ox,oy));
bad(()->nativePredictiveFrameInterpImpl(new float[0],prev,one,one,.05f,1,ox,oy));
float[] stroke=new float[3];nativeDirectInputHookImpl(new float[]{-2},new float[]{200},new float[]{2.75f},1,100,100,stroke);require(stroke[0]==0&&stroke[1]==100&&stroke[2]==2);
bad(()->nativeDirectInputHookImpl(one,one,one,1,100,100,new float[2]));
bad(()->nativeDirectInputHookImpl(one,one,new float[]{Float.NaN},1,100,100,stroke));
java.nio.ByteBuffer a=java.nio.ByteBuffer.allocateDirect(32),b=java.nio.ByteBuffer.allocateDirect(32);
for(int i=0;i<32;i++)b.put(i,(byte)255);
require(nativeComputeOpticalVariance(a,b,2,2,16,0,0,2,2)==1f);
require(nativeComputeOpticalVariance(a,b,2,2,4,0,0,2,2)==0f);
require(nativeComputeOpticalVariance(a,java.nio.ByteBuffer.allocateDirect(1),2,2,16,0,0,2,2)==0f);
require(nativeComputeOpticalVariance(a,b,2,2,16,Integer.MAX_VALUE,0,Integer.MAX_VALUE,2)==0f);
require(nativePreprocessFrame(a,java.nio.ByteBuffer.allocateDirect(48),2,2,4,2,0)<0);
require(nativePreprocessFrame(a,java.nio.ByteBuffer.allocateDirect(48),2,2,16,Integer.MAX_VALUE,0)<0);
bad(()->nativeAnalyzeFrame(a,2,2,16,4,new float[66]));
bad(()->nativeAnalyzeFrame(java.nio.ByteBuffer.allocateDirect(1),2,2,16,4,new float[67]));
float[] frame=new float[67];nativeAnalyzeFrame(a,2,2,16,4,frame);require(frame[0]==0);
java.util.concurrent.atomic.AtomicReference<Throwable> error=new java.util.concurrent.atomic.AtomicReference<>();
Thread[] threads=new Thread[2];for(int k=0;k<2;k++){final int tag=k+1;threads[k]=new Thread(()->{try{for(int i=0;i<1000;i++){float[] values={tag},out=new float[3];nativeDirectInputHookImpl(values,values,values,1,100,100,out);if(out[0]!=tag||out[1]!=tag)throw new AssertionError("race");
java.nio.ByteBuffer pixels=java.nio.ByteBuffer.allocateDirect(256);if(tag==2)for(int j=0;j<64;j++)pixels.put(j*4+1,(byte)255);
float[] found=new float[67];nativeAnalyzeFrame(pixels,8,8,32,4,found);if(found[0]!=(tag==2?22:0))throw new AssertionError("frame race");}}catch(Throwable e){error.set(e);}});threads[k].start();}for(Thread thread:threads)thread.join();require(error.get()==null);
'''
text += '''// Valid-input-only methods (no splendor_reject contract in C: they return
// silently / -1 on bad input, so bad() does not apply -- C-side gap, Task 5).
long packed=nativeAuthorityArbitrate(1,100f,200f,0f,0f,0f,0f,1f,1f,0f,1f,1f);
require(Float.intBitsToFloat((int)(packed>>>32))==200f&&Float.intBitsToFloat((int)packed)==256f);
float[] shield=new float[5];
nativeBallRetentionShieldCompute(true,true,1f,100f,200f,1,200f,300f,0,0,0,0.1f,2,1650f,720f,shield);
require(shield[0]==1f&&shield[1]==160f&&shield[2]==260f&&shield[3]==0.8f&&shield[4]==0.5f);
float[] icept=new float[5];
nativeInstantInterceptCompute(false,true,1f,true,10f,20f,5f,5f,false,100f,100f,icept);
require(icept[0]==1f&&icept[1]==20f&&icept[2]==30f&&icept[3]==1f&&Math.abs(icept[4]-(float)Math.sqrt(11300.0))<0.001f);
float[] press=new float[4];
nativeBuildUpPressCompute(false,true,1f,true,50f,60f,false,100f,100f,press);
require(press[0]==1f&&press[1]==50f&&press[2]==60f&&press[3]==1f);
java.nio.ByteBuffer px=java.nio.ByteBuffer.allocateDirect(256);
require(nativeExtractBlobs(px,8,8,32,4,0.5f,new int[8])>=0);
require(nativeExtractBlobs(null,8,8,32,4,0.5f,new int[8])==-1);
require(nativeExtractBlobs(px,8,8,32,4,Float.NaN,new int[8])==-1);
require(nativeExtractBlobs(px,8,8,32,4,0.5f,new int[0])==-1);
System.out.println("PASS JNI checks="+checks+"; concurrent distinct-input calls=4000 (stroke + frame); static contracts=3 (breadcrumb dump + 2 input bridge)");
}}'''
(t / 'NativeBridge.java').write_text(text)

# Static-only contracts: C signature must match the Kotlin declaration.
# nativeBreadcrumbDump lives in native_breadcrumb.c (<android/log.h>: no host
# compile); the input bridge lives in native_input.cpp (NDK-only headers).
def static_contract(c_file, c_pat, kt_file, kt_decl, what):
    c = (r / c_file).read_text()
    k = (r / kt_file).read_text()
    assert re.search(c_pat, c), what + ': C signature missing in ' + c_file
    assert kt_decl in k, what + ': Kotlin declaration missing in ' + kt_file
static_contract('app/src/main/cpp/native_breadcrumb.c',
                r'JNIEXPORT\s+jstring\s+JNICALL\s+Java_com_assistant_NativeBridge_nativeBreadcrumbDump\(\s*JNIEnv\s*\*\s*env,\s*jclass\s+clazz\s*\)',
                'app/src/main/java/com/assistant/NativeBridge.kt',
                'external fun nativeBreadcrumbDump(): String', 'nativeBreadcrumbDump')
static_contract('app/src/main/cpp/native_input.cpp',
                r'Java_com_assistant_input_NativeInputBridge_nativeInjectTap\(\s*JNIEnv\*\s*env,\s*jobject[^,]*,\s*jfloat\s+x,\s*jfloat\s+y\s*\)',
                'app/src/main/java/com/assistant/input/NativeInputBridge.kt',
                'external fun nativeInjectTap(x: Float, y: Float): Boolean', 'nativeInjectTap')
static_contract('app/src/main/cpp/native_input.cpp',
                r'Java_com_assistant_input_NativeInputBridge_nativeInjectSwipe\(\s*JNIEnv\*\s*env,\s*jobject[^,]*,\s*jfloat\s+startX,\s*jfloat\s+startY,\s*jfloat\s+endX,\s*jfloat\s+endY,\s*jlong\s+duration\s*\)',
                'app/src/main/java/com/assistant/input/NativeInputBridge.kt',
                'external fun nativeInjectSwipe(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long): Boolean', 'nativeInjectSwipe')

# JDK: $JAVA_HOME, else ~/jdk17, else a clear failure (never a bare KeyError).
def find_jdk():
    for cand in [os.environ.get('JAVA_HOME'), str(Path.home() / 'jdk17'), '/usr/lib/jvm/default-java']:
        if cand and (Path(cand) / 'include' / 'jni.h').is_file():
            return Path(cand)
    raise SystemExit('FAIL: no JDK found (need include/jni.h). Set JAVA_HOME to a JDK.')
java = find_jdk()
cc = shutil.which('cc') or shutil.which('gcc')
if not cc:
    raise SystemExit('FAIL: no C compiler (cc/gcc) on PATH.')
cmd = [cc, '-shared', '-fPIC', '-std=c11', '-Wall', '-Wextra', '-Werror', '-Wno-unused-parameter', '-I' + str(java / 'include'), '-I' + str(java / 'include' / 'linux'), '-I' + str(r / 'app/src/main/cpp'), *[str(r / 'app/src/main/cpp' / f) for f in files], '-lm', '-o', str(t / 'proof.so')]
subprocess.run(cmd, check=True)
subprocess.run(['javac', '-d', str(t), str(t / 'NativeBridge.java')], check=True)
subprocess.run(['java', '-Xcheck:jni', '-Dproof.lib=' + str(t / 'proof.so'), '-cp', str(t), 'com.assistant.NativeBridge'], check=True)
