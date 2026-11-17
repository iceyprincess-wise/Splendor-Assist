package com.assistant.vision

import android.graphics.Rect
import android.view.View
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * GAP1 — SELF MASK
 *
 * Every view this app paints through WindowManager reports its on-screen
 * rectangle here. The capture/OCR path then ignores anything inside those
 * rectangles, so the app stops reading its own HUD as if it were game content.
 *
 * No62-D6: this object is the ONLY self-mask store. An earlier parallel
 * mirror (VisionOverlayRegistry.selfRects, written from publishView/clear
 * and read by nobody — its isSelfDrawn/selfMask/selfMaskCount had zero
 * callers repo-wide and VisionOverlayRegistryState exposes no mask field)
 * was removed, so the two stores can never desync again.
 */
object OverlaySelfMask {

    private const val EDGE_PAD = 6   // catches antialiased borders / shadow

    private val rects = ConcurrentHashMap<String, Rect>()
    private val loc = IntArray(2)

    // No62-D1: hits/passes/guardRejects are written from two threads
    // (the vision coroutine at OverlayService:347 and the OCR callback at
    // OverlayService:1048) and read from stats()/scaleInfo(). @Volatile
    // Long ++ is a read-modify-write, so concurrent increments could be
    // lost; AtomicLong matches ocrTicks below, which was already atomic.
    private val hits = AtomicLong(0L)
    private val passes = AtomicLong(0L)

    @JvmStatic
    fun publishView(tag: String, v: View?) {
        if (v == null || !v.isShown || v.width <= 0 || v.height <= 0) {
            clear(tag); return
        }
        synchronized(loc) {
            v.getLocationOnScreen(loc)
            val r = Rect(
                loc[0] - EDGE_PAD,
                loc[1] - EDGE_PAD,
                loc[0] + v.width + EDGE_PAD,
                loc[1] + v.height + EDGE_PAD
            )
            rects[tag] = r
        }
    }

    @JvmStatic
    fun clear(tag: String) {
        rects.remove(tag)
    }

    @JvmStatic
    fun clearAll() {
        for (t in rects.keys.toList()) clear(t)
    }

    /** true when this screen point was painted by us, not by the game */
    @JvmStatic
    fun isSelfDrawn(x: Int, y: Int): Boolean {
        if (rects.isEmpty()) { passes.incrementAndGet(); return false }
        for (r in rects.values) {
            if (r.contains(x, y)) { hits.incrementAndGet(); return true }
        }
        passes.incrementAndGet()
        return false
    }

    /** centre-point test for an OCR / detection bounding box */
    @JvmStatic
    fun isSelfDrawn(box: Rect?): Boolean {
        if (box == null) return false
        return isSelfDrawn(box.centerX(), box.centerY())
    }

    @JvmStatic
    fun mask(): List<Rect> = rects.values.toList()

    @JvmStatic
    fun count(): Int = rects.size

    @JvmStatic
    fun stats(): String = "selfMask rects=" + rects.size + " rejected=" + hits.get() + " passed=" + passes.get()

    @JvmStatic
    fun resetStats() { hits.set(0L); passes.set(0L) }

    // ================= GAP1B: capture-space mapping =================

    @Volatile private var scaleX = 1f
    @Volatile private var scaleY = 1f
    private val guardRejects = AtomicLong(0L)

    @JvmStatic
    fun setCaptureScale(captureW: Int, captureH: Int, screenW: Int, screenH: Int) {
        // No62: a non-positive dimension would make the capture->screen
        // division divide by ~0 (or flip the sign). The only caller
        // (OverlayService:910) already guards, so this is defence in
        // depth: ignore the bad call and keep the previous scale instead
        // of silently storing a broken one.
        if (screenW > 0 && screenH > 0 && captureW > 0 && captureH > 0) {
            scaleX = captureW.toFloat() / screenW.toFloat()
            scaleY = captureH.toFloat() / screenH.toFloat()
        }
    }

    /** point test in CAPTURED-IMAGE coordinates (what OCR / detection reports) */
    @JvmStatic
    fun isSelfDrawnCapture(cx: Int, cy: Int): Boolean {
        val sx = if (scaleX > 0f) (cx / scaleX).toInt() else cx
        val sy = if (scaleY > 0f) (cy / scaleY).toInt() else cy
        return isSelfDrawn(sx, sy)
    }

    @JvmStatic
    fun isSelfDrawnCapture(box: Rect?): Boolean {
        if (box == null) return false
        return isSelfDrawnCapture(box.centerX(), box.centerY())
    }

    // ============ GAP1B: publish only painted LEAF views ============

    /**
     * Publishes every VISIBLE LEAF view under [root] that actually paints content.
     * The root container is deliberately skipped: it is MATCH_PARENT, and masking
     * it would reject the entire screen. Any view larger than [maxAreaFraction]
     * of the root is also skipped as a container guard.
     */
    @JvmStatic
    @JvmOverloads
    fun publishHierarchy(tag: String, root: View?, maxAreaFraction: Float = 0.45f) {
        if (root == null) { clearPrefix(tag); return }
        // No62-D3: a root that has not been laid out yet is 0x0. The old
        // code walked it anyway: rootArea coerced to 1 made every leaf
        // fail the area guard (guardRejects++ each) and the empty `seen`
        // list then cleared the whole tag mask — one premature call
        // wiped the HUD mask. Keep the existing mask until layout lands;
        // the overlay is never GONE (verified in OverlayService), so 0x0
        // here is only transient pre-layout, never a hidden overlay.
        if (root.width <= 0 || root.height <= 0) return
        // No62-D5: the fraction is a caller-supplied guard bound. Above
        // 1 it can never fire (the container guard is off); at or below
        // 0 it rejects every leaf. Clamp it to a real fraction.
        val frac = maxAreaFraction.coerceIn(0f, 1f)
        val rootArea = (root.width.toLong() * root.height.toLong()).coerceAtLeast(1L)
        val seen = ArrayList<String>()
        walk(tag, root, root, rootArea, frac, seen, 0)
        for (k in rects.keys.toList()) {
            if (k.startsWith(tag + "/") && !seen.contains(k)) clear(k)
        }
    }

    private fun walk(
        tag: String, root: View, v: View,
        rootArea: Long, maxFrac: Float,
        seen: MutableList<String>, depth: Int
    ) {
        // No62-D4: the old code returned silently here, so a view deeper
        // than 12 levels vanished from the mask with no trace. It is a
        // guard rejection — count it like the area guard below.
        if (depth > 12) { guardRejects.incrementAndGet(); return }
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) {
                walk(tag, root, v.getChildAt(i), rootArea, maxFrac, seen, depth + 1)
            }
            return
        }
        if (v === root) return
        if (!v.isShown || v.width <= 0 || v.height <= 0) return

        val area = v.width.toLong() * v.height.toLong()
        if (area.toFloat() / rootArea.toFloat() > maxFrac) { guardRejects.incrementAndGet(); return }

        // No62-D2: the old key was tag + "/" + resource-entry name, so
        // two leaves sharing one id in different parents (e.g. repeated
        // rows) overwrote each other's rect and one view went unmasked.
        // The key now carries the depth + position suffix the no-id
        // branch used to carry inline, so identical ids in
        // different places get different keys and a moved view gets a
        // fresh key (its old key is cleared by the publishHierarchy
        // sweep, which is what makes the mask follow the view).
        val name = if (v.id != View.NO_ID) {
            try { v.resources.getResourceEntryName(v.id) } catch (t: Throwable) { "v" + v.id }
        } else {
            v.javaClass.simpleName
        }
        val key = tag + "/" + name + "@" + depth + "_" + v.left + "_" + v.top
        seen.add(key)
        publishView(key, v)
    }

    @JvmStatic
    fun clearPrefix(prefix: String) {
        for (k in rects.keys.toList()) {
            if (k == prefix || k.startsWith(prefix + "/")) clear(k)
        }
    }

    @JvmStatic
    fun scaleInfo(): String = "captureScale x=" + scaleX + " y=" + scaleY + " guardRejects=" + guardRejects.get()

    // ---- GAP1B: periodic runtime proof ----
    private val ocrTicks = java.util.concurrent.atomic.AtomicLong(0L)

    @JvmStatic
    fun tickAndLog(every: Long = 20L) {
        val n = ocrTicks.incrementAndGet()
        if (n % every != 0L) return
        try {
            com.assistant.diagnostic.RuntimeLogger.log(stats() + " | " + scaleInfo(), "SELFMASK")
        } catch (_: Throwable) { }
    }
}
