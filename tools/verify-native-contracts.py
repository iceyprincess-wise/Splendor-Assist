#!/usr/bin/env python3
from pathlib import Path
import re,subprocess,os,json,tempfile
r=Path(__file__).resolve().parents[1];scratch=tempfile.TemporaryDirectory(prefix='splendor-jni-');t=Path(scratch.name)
files=['native_agility_physics.c','native_critical_vector.c','native_kicking_posture.c','native_dominance_matrix.c','native_stopper_matrix.c','native_true_shot.c','native_sa_corrector.c','native_tactical_engines.c','native_direct_input_hook.c','native_predictive_frame_interp.c','native_optical_variance.c','native_vision_preprocessor.c','native_frame_analyzer.c']
methods=[]
for file in files:
 s=(r/'app/src/main/cpp'/file).read_text()
 for m in re.finditer(r'JNIEXPORT\s+(\w+)\s+JNICALL\s+Java_com_assistant_NativeBridge_(\w+)\s*\((.*?)\)\s*\{',s,re.S):
  ret,name,ps=m.groups();params=[p.strip().split() for p in ps.split(',')][2:];methods.append((name,ret,params))
maptype={'jfloat':'float','jboolean':'boolean','jfloatArray':'float[]','jint':'int','jobject':'java.nio.ByteBuffer'}
text='package com.assistant;\npublic class NativeBridge { static { System.load(System.getProperty("proof.lib")); }\n'
for name,ret,ps in methods:
 text+='static native '+{'void':'void','jfloat':'float','jint':'int'}[ret]+' '+name+'('+','.join(maptype[typ]+' '+n for typ,n in ps)+');\n'
text+='static int checks=0; static void require(boolean b){if(!b)throw new AssertionError("check "+checks); checks++;}\nstatic void bad(Runnable f){try{f.run();throw new AssertionError("invalid JNI contract accepted");}catch(IllegalArgumentException expected){checks++;}}\npublic static void main(String[] args) throws Exception {\n'
lengths={'nativeAgilityPhysicsImpl':6,'nativeCriticalScoringVector':2,'nativeCriticalTrueTargetPass':2,'nativeKickingPostureImpl':4,'nativeComputeDominanceVectors':4,'nativeExecuteStopperMatrix':4,'nativeTrueShotCompute':5,'nativeSACorrectPass':3,'nativeSACorrectShot':4,'nativeSACorrectCross':4,'nativeSACorrectKeeper':3,'nativeTacticalAnalytics':1,'nativeTacticalBehavior':1,'nativeRuntimeConfidenceCalibration':1,'nativeDefensiveCompactness':4}
for name,ret,ps in methods:
 if name not in lengths:continue
 size=lengths[name]
 def args(arr='out',nan=False):
  a=['100f' if typ=='jfloat' else 'true' if typ=='jboolean' else '1' if typ=='jint' else arr for typ,n in ps]
  if nan:a[next(i for i,(typ,n) in enumerate(ps) if (n=='nearUnits' if name=='nativeAgilityPhysicsImpl' else typ=='jfloat'))]='Float.NaN'
  return ','.join(a)
 text+='{float[] out=new float['+str(size)+'];'+name+'('+args()+');for(float v:out)require(Float.isFinite(v));\n'
 text+='bad(()->'+name+'('+args('null')+'));bad(()->'+name+'('+args('new float['+str(size-1)+']')+'));bad(()->'+name+'('+args(nan=True)+'));}\n'
text+='''float[] one={2}, prev={1}, ox={-1}, oy={-1};
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
System.out.println("PASS JNI checks="+checks+"; concurrent distinct-input calls=4000 (stroke + frame)");
}}'''
(t/'NativeBridge.java').write_text(text)
java=Path(os.environ['JAVA_HOME']);cmd=['cc','-shared','-fPIC','-std=c11','-Wall','-Wextra','-Werror','-Wno-unused-parameter','-I'+str(java/'include'),'-I'+str(java/'include/linux'),'-I'+str(r/'app/src/main/cpp'),*[str(r/'app/src/main/cpp'/f) for f in files],'-lm','-o',str(t/'proof.so')]
subprocess.run(cmd,check=True);subprocess.run(['javac','-d',str(t),str(t/'NativeBridge.java')],check=True);subprocess.run(['java','-Xcheck:jni','-Dproof.lib='+str(t/'proof.so'),'-cp',str(t),'com.assistant.NativeBridge'],check=True)
