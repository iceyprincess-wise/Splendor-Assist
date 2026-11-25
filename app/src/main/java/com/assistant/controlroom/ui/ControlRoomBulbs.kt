package com.assistant.controlroom.ui

import android.content.Context
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt

/**
 * Shared new-UI bulb kit for the inner control rooms (Smart Assist,
 * Interception, Goalkeeper) — the same dashboard setup as MainActivity's
 * bulb grid (UI-1): a coloured dot + bold label + live value inside a
 * panel_surface box, two boxes per row, sections under an accent header.
 *
 * Label/value text uses ThemeTokens plain ints, never a theme attribute:
 * the UI-BULBS fix proved a framework text-colour attribute resolves to
 * a ColorStateList whose TypedValue.data is 0 — setTextColor(0) renders
 * invisible text. Plain ints cannot hit that bug.
 *
 * BulbState rule (same as MainActivity): GREEN = true/alive (or a live
 * counter > 0), YELLOW = false/waiting (or a counter at 0), RED = an
 * issue (a failed/rejected counter > 0, or a non-empty degraded list).
 * Every value comes from the live snapshot maps the caller passes in —
 * nothing here is hardcoded.
 */
object ControlRoomBulbs {

    enum class BulbState { GREEN, YELLOW, RED }

    data class BulbEntry(val label: String, val state: BulbState, val value: String)

    fun color(state: BulbState): Int = when (state) {
        BulbState.GREEN -> "#00E676".toColorInt()
        BulbState.YELLOW -> "#FFC107".toColorInt()
        BulbState.RED -> "#FF5252".toColorInt()
    }

    fun dp(context: Context, v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()

    /** Section header: accent label + hairline, same as DiagnosisRoom.section(). */
    fun sectionHeader(context: Context, title: String): LinearLayout {
        val wrap = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(context, 16); bottomMargin = dp(context, 4) }
        }
        wrap.addView(TextView(context).apply {
            text = title
            textSize = 13f
            setTextColor(ThemeTokens.ACCENT_TEXT)
            setTypeface(typeface, Typeface.BOLD)
        })
        wrap.addView(View(context).apply {
            setBackgroundColor(ThemeTokens.SURFACE)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 1)
            ).apply { topMargin = dp(context, 4) }
        })
        return wrap
    }

    /** One bulb cell: dot + bold label row, live value below. */
    fun buildBulbCell(context: Context, entry: BulbEntry): LinearLayout {
        val cell = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ).apply { marginStart = dp(context, 4); marginEnd = dp(context, 4) }
            setBackgroundResource(com.assistant.overlay.R.drawable.panel_surface)
            setPadding(dp(context, 12), dp(context, 12), dp(context, 12), dp(context, 12))
        }
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        top.addView(TextView(context).apply {
            text = "●"
            textSize = 14f
            setTextColor(color(entry.state))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(context, 6) }
        })
        top.addView(TextView(context).apply {
            text = entry.label
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(ThemeTokens.TEXT)
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        })
        cell.addView(top)
        cell.addView(TextView(context).apply {
            text = entry.value
            textSize = 11f
            setTextColor(ThemeTokens.TEXT_MUTED)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(context, 2) }
        })
        return cell
    }

    /** Renders entries two-per-row into a vertical container (clears it first). */
    fun renderBulbSection(container: LinearLayout, entries: List<BulbEntry>) {
        val context = container.context
        entries.chunked(2).forEach { row ->
            val rowLayout = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(context, 4); bottomMargin = dp(context, 4) }
            }
            row.forEach { entry -> rowLayout.addView(buildBulbCell(context, entry)) }
            if (row.size == 1) {
                rowLayout.addView(View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                })
            }
            container.addView(rowLayout)
        }
    }

    /** One full-width bulb cell (gate banners). */
    fun renderFullWidthCell(container: LinearLayout, entry: BulbEntry) {
        val context = container.context
        val cell = buildBulbCell(context, entry)
        (cell.layoutParams as LinearLayout.LayoutParams).apply {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            weight = 0f
            topMargin = dp(context, 4)
            bottomMargin = dp(context, 4)
        }
        container.addView(cell)
    }

    /** Small mono text block for long bounded values (registry names, capabilities). */
    fun monoBlock(context: Context, text: String): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 11f
            setTextColor(ThemeTokens.TEXT_DIM)
            setTypeface(Typeface.MONOSPACE)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(context, 4) }
        }

    // Snapshot-map readers (no invented keys: a missing key reads as
    // false / 0 / "—", never as a made-up value).
    fun bool(map: Map<String, Any>, key: String): Boolean = map[key] as? Boolean ?: false
    fun count(map: Map<String, Any>, key: String): Long = (map[key] as? Number)?.toLong() ?: 0L
    fun text(map: Map<String, Any>, key: String): String = map[key]?.toString() ?: "—"

    /** Boolean bulb: true = GREEN, false = YELLOW. */
    fun boolBulb(map: Map<String, Any>, key: String, label: String, on: String = "Ready", off: String = "Waiting"): BulbEntry {
        val v = bool(map, key)
        return BulbEntry(label, if (v) BulbState.GREEN else BulbState.YELLOW, if (v) on else off)
    }

    /** Counter bulb: > 0 = GREEN, 0 = YELLOW. */
    fun countBulb(map: Map<String, Any>, key: String, label: String): BulbEntry {
        val v = count(map, key)
        return BulbEntry(label, if (v > 0) BulbState.GREEN else BulbState.YELLOW, v.toString())
    }

    /** Failure-counter bulb: > 0 = RED, 0 = GREEN. */
    fun failBulb(map: Map<String, Any>, key: String, label: String): BulbEntry {
        val v = count(map, key)
        return BulbEntry(label, if (v > 0) BulbState.RED else BulbState.GREEN, v.toString())
    }

    /** Issues bulb from the health snapshot's degradedReasons (a joined string). */
    fun issuesBulb(health: Map<String, Any>): BulbEntry {
        val reasons = health["degradedReasons"]?.toString().orEmpty().trim()
        return if (reasons.isEmpty() || reasons == "[]") {
            BulbEntry("Issues", BulbState.GREEN, "none")
        } else {
            BulbEntry("Issues", BulbState.RED, reasons)
        }
    }
}
