package com.assistant.vision

import android.graphics.Rect
import android.view.View
import java.util.concurrent.ConcurrentHashMap

/**
 * GAP1 — SELF MASK
 *
 * Every view this app paints through WindowManager reports its on-screen
 * rectangle here. The capture/OCR path then ignores anything inside those
 * rectangles, so the app stops reading its own HUD as if it were game content.
 */
object OverlaySelfMask {

    private const val EDGE_PAD = 6   // catches antialiased borders / shadow

    private val rects = ConcurrentHashMap<String, Rect>()
    private val loc = IntArray(2)

    @Volatile private var hits = 0L
    @Volatile private var passes = 0L

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
            com.assistant.adapter.smartassist.VisionOverlayRegistry.publishBounds(tag, r)
        }
    }

    @JvmStatic
    fun clear(tag: String) {
        rects.remove(tag)
        com.assistant.adapter.smartassist.VisionOverlayRegistry.clearBounds(tag)
    }

    @JvmStatic
    fun clearAll() {
        for (t in rects.keys.toList()) clear(t)
    }

    /** true when this screen point was painted by us, not by the game */
    @JvmStatic
    fun isSelfDrawn(x: Int, y: Int): Boolean {
        if (rects.isEmpty()) { passes++; return false }
        for (r in rects.values) {
            if (r.contains(x, y)) { hits++; return true }
        }
        passes++
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
    fun stats(): String = "selfMask rects=" + rects.size + " rejected=" + hits + " passed=" + passes

    @JvmStatic
    fun resetStats() { hits = 0L; passes = 0L }

    // ================= GAP1B: capture-space mapping =================

    @Volatile private var scaleX = 1f
    @Volatile private var scaleY = 1f
    @Volatile private var guardRejects = 0L

    @JvmStatic
    fun setCaptureScale(captureW: Int, captureH: Int, screenW: Int, screenH: Int) {
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
        val rootArea = (root.width.toLong() * root.height.toLong()).coerceAtLeast(1L)
        val seen = ArrayList<String>()
        walk(tag, root, root, rootArea, maxAreaFraction, seen, 0)
        for (k in rects.keys.toList()) {
            if (k.startsWith(tag + "/") && !seen.contains(k)) clear(k)
        }
    }

    private fun walk(
        tag: String, root: View, v: View,
        rootArea: Long, maxFrac: Float,
        seen: MutableList<String>, depth: Int
    ) {
        if (depth > 12) return
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) {
                walk(tag, root, v.getChildAt(i), rootArea, maxFrac, seen, depth + 1)
            }
            return
        }
        if (v === root) return
        if (!v.isShown || v.width <= 0 || v.height <= 0) return

        val area = v.width.toLong() * v.height.toLong()
        if (area.toFloat() / rootArea.toFloat() > maxFrac) { guardRejects++; return }

        val name = if (v.id != View.NO_ID) {
            try { v.resources.getResourceEntryName(v.id) } catch (t: Throwable) { "v" + v.id }
        } else {
            v.javaClass.simpleName + "@" + depth + "_" + v.left + "_" + v.top
        }
        val key = tag + "/" + name
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
    fun scaleInfo(): String = "captureScale x=" + scaleX + " y=" + scaleY + " guardRejects=" + guardRejects

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
