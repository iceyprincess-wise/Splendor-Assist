package com.assistant

// f19 pure Kotlin equivalents of the tiny JNI formulas, preserving C float op order.
object NativePorts {
    private fun coerce(v: Float, lo: Float, hi: Float): Float {
        var r = v
        r = 0.5f * (r + lo + kotlin.math.abs(r - lo))
        r = 0.5f * (r + hi - kotlin.math.abs(hi - r))
        return r
    }
    fun cross(x: Float, y: Float, strength: Int, out: FloatArray) {
        val boost = coerce(strength.toFloat(), 0f, 100f) * 0.01f
        out[0] = x
        out[1] = y - (40f * boost)
        out[2] = coerce(0.60f + (boost * 0.40f), 0f, 1f)
    }
    fun crossLead(v: Float): Float = v * 0.5f
    fun crossSwipe(): Float = 320f
    fun crossDuration(): Long = 45L
    fun keeper(zone: Int, direction: Int, priority: Int, x: Float, y: Float, out: FloatArray) {
        var bias = 0
        if (zone == 4) bias = 1 else if (direction == 0) bias = 2 else if (direction == 1) bias = 3 else if (zone == 0) bias = 4 else if (zone == 1) bias = 5
        val offset = when (bias) { 2 -> -55f; 3 -> 55f; 4 -> -40f; 5 -> 40f; else -> 0f }
        val valid = !(offset == 0f && bias != 0)
        var authority = priority / 150f
        if (authority < 0f) authority = 0f
        if (authority > 1f) authority = 1f
        out[0] = if (valid) 1f else 0f; out[1] = x; out[2] = y + offset; out[3] = authority
    }
    fun panic(direction: Int, priority: Int, out: FloatArray) {
        var action = 0
        if (priority >= 130) { if (direction == 0) action = 2 else if (direction == 1) action = 1 else action = 3 }
        out[0] = action.toFloat()
    }
    fun shot(distance: Float, pressure: Float, out: FloatArray) {
        out[0] = coerce(1f - (distance * 0.001f), 0f, 1f)
        out[1] = coerce((1f - pressure) * 4f, 0f, 4f)
        out[2] = coerce(pressure, 0f, 1f)
    }
    fun threat(ts: Int,z: Int,d: Int,aw: Int,pr: Int,ib: Int,rb: Int,x: Int,y: Int,w: Int,h: Int,out: FloatArray) {
        val d1=if(d==1)1f else 0f; val d2=if(d==2)1f else 0f
        val db=(d1*35f)+(d2*30f)
        val z1=if(z==1)1f else 0f; val z2=if(z==2)1f else 0f; val z3=if(z==3)1f else 0f
        val zb=(z1*40f)+(z2*25f)+(z3*10f)
        val priority=ts+db.toInt()+(aw shr 1)+(pr shr 1)+ib+rb+zb.toInt()
        val ih=if(h>0)1f/h.toFloat() else 0f; val iw=if(w>0)1f/w.toFloat() else 0f
        val ny=y.toFloat()*ih; val nx=x.toFloat()*iw
        val hm=coerce((ny-0.80f)*10f,0f,1f); val lm=coerce((0.40f-ny)*10f,0f,1f)
        val mm=(1f-hm)*(1f-lm)
        val band=(hm*2f+mm*1f+lm*0f).toInt()
        out[0]=priority.toFloat(); out[1]=band.toFloat(); out[2]=coerce(nx,0f,1f)
    }
    private fun select(cond: Float,a: Float,b: Float)= (cond*a)+((1f-cond)*b)
    private fun cmax(x: Float, lo: Float): Float = if (x.isNaN()) lo else kotlin.math.max(lo,x)
    private fun cmin(x: Float, hi: Float): Float = if (x.isNaN()) hi else kotlin.math.min(hi,x)
    private fun clip(x: Float,lo: Float,hi: Float)=cmin(cmax(x,lo),hi)
    fun speed(distance: Float,angle: Float,strength: Int,aj: Float,en: Float,pn: Float,prn: Float,ln: Float,out: FloatArray) {
        val factor=coerce(strength.toFloat(),0f,100f)*0.01f
        val dn=coerce(distance,0f,1000f)*0.001f
        val aa=kotlin.math.abs(angle); val diff=aa-45f
        val cond=clip(diff*10000f,0f,1f)
        val ca=15f+aj; val cb=-15f+aj; val containment=select(cond,ca,cb)
        val prox=1f-dn
        out[0]=containment
        out[1]=coerce((10f+(factor*15f)+(prox*9f))+en,10f,35f)
        out[2]=coerce((factor*15f+prox*7f)+pn,0f,20f)
        out[3]=coerce((factor*15f+prox*7f)+prn,0f,20f)
        out[4]=coerce((factor*14f+prox*8f)+ln,0f,20f)
    }
    fun claim(direction: Int, priority: Int): Int { if(direction!=3)return 0; if(priority>=140)return 2; if(priority>=100)return 1; return 0 }
    fun vector(width: Float,height: Float,out: FloatArray) { out[0]=width*0.50f;out[1]=height*0.82f;out[2]=width*0.50f;out[3]=height*0.35f }
}

// Kotlin ports of two tiny native formulas (f17). Op-for-op mirrors of the C bodies.
// UNVERIFIED: not compiled in the build sandbox; compile happens on the user's Termux build.

object VectorSmootherPort {
    // same seed as native_vector_smoother.c: static uint32_t g_rng_state = 123456789;
    private var rngState: Int = 123456789

    private fun xorshift32(): Int {
        var x = rngState
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        rngState = x
        return x
    }

    // Intentional behavior change from the f17 bit-for-bit port: the C expression
    // subtracted in uint32 and wrapped (e.g. 0u - 10u became 4294967286f).
    // Jitter intent is signed range [-sub, m-sub-1], so subtract after modulo.
    // This removes wrap-sized cursor jumps while retaining the xorshift sequence.
    private fun unsignedModMinusToFloat(x: Int, m: Int, sub: Int): Float {
        val r = Integer.remainderUnsigned(x, m)
        return (r - sub).toFloat()
    }

    fun smooth(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Float, out: FloatArray) {
        if (out.size < 33) return
        val steps = 10
        val dt = 1.0f / steps.toFloat()
        val wobbleAmp = 1.5f
        for (i in 0..steps) {
            val t = i.toFloat() * dt
            val cx = (startX + endX) * 0.5f + unsignedModMinusToFloat(xorshift32(), 20, 10)
            val cy = (startY + endY) * 0.5f + unsignedModMinusToFloat(xorshift32(), 20, 10)
            val u = 1.0f - t
            val bx = u * u * startX + 2.0f * u * t * cx + t * t * endX
            val by = u * u * startY + 2.0f * u * t * cy + t * t * endY
            val wx = unsignedModMinusToFloat(xorshift32(), 200, 100) * 0.01f * wobbleAmp
            val wy = unsignedModMinusToFloat(xorshift32(), 200, 100) * 0.01f * wobbleAmp
            val idx = i * 3
            out[idx] = bx + wx
            out[idx + 1] = by + wy
            out[idx + 2] = t * durationMs
        }
    }
}

object CounterattackPort {
    private fun branchlessCoerce(value: Float, minVal: Float, maxVal: Float): Float {
        var r = value
        r = 0.5f * (r + minVal + Math.abs(r - minVal))
        r = 0.5f * (r + maxVal - Math.abs(maxVal - r))
        return r
    }

    fun analyze(
        attackers: Int, teamShapeFound: Boolean, teamShapeConf: Float,
        offensiveLineFound: Boolean, offensiveLineConf: Float, out: FloatArray
    ) {
        if (out.size < 2) return
        var tsc = teamShapeConf
        var olc = offensiveLineConf
        if (!tsc.isFinite()) tsc = 0.0f
        if (!olc.isFinite()) olc = 0.0f
        val shapeMask = if (teamShapeFound) 1.0f else 0.0f
        val lineMask = if (offensiveLineFound) 1.0f else 0.0f
        var conf = (attackers.toFloat() * 0.090909f) + (shapeMask * 0.15f) +
            (lineMask * 0.15f) + (tsc * 0.05f) + (olc * 0.05f)
        if (!conf.isFinite()) conf = 0.0f
        conf = branchlessCoerce(conf, 0.0f, 1.0f)
        val amplified = conf * conf * (3.0f - 2.0f * conf)
        out[0] = amplified
        out[1] = conf
    }
}
