package com.assistant.diagnostic

/*
 * no59 — ITEM 2: the grouped short-location log format (Ed's rule).
 *
 * THE RULE (Ed's example, followed precisely):
 *
 *   NOT this (the old way — full path + line on EVERY line):
 *     app/AgilityContributor.kt:123 [Dispatch] blah blah blah
 *     app/AgilityContributor.kt:123 [Dispatch] blah blah blah
 *     app/MageneticFeetEngine.kt:150 [Dispatch] blah blah blah
 *
 *   INSTEAD this (the new way — location once per location run):
 *     Timestamp: app/AgilityContributor.kt:123:
 *     [Dispatch] blah blah blah
 *     [Dispatch] blah blah blah
 *     Timestamp: app/MageneticFeetEngine.kt:150:
 *     [Dispatch] blah blah blah
 *
 * Precise grouping rules (implemented by Grouper below):
 *   1. The logger keeps the last-printed location per destination stream.
 *      When a log call comes from a DIFFERENT location, it prints a new
 *      header "<timestamp>: <location>:" first, then the message line.
 *      When the SAME location repeats, it prints just the message line.
 *   2. An interleaved location forces a re-header: A, then B, then A again
 *      prints a fresh header for the second A run, because the location
 *      changed. The grouper remembers only the immediately previous
 *      location, never a history.
 *   3. A time gap does NOT force a new header. Only a location change
 *      does. (A gap rule would need an arbitrary threshold and would
 *      re-print headers on every quiet period; the location rule alone
 *      is deterministic and is what Ed's example shows.)
 *   4. The header timestamp uses the repo's existing convention
 *      (yyyy-MM-dd HH:mm:ss.SSS, the ThreadLocal formatter in
 *      RuntimeLogger) — no new clock is invented.
 *   5. The short location is "<module>/<File>.<ext>:<line>" where module
 *      is "app" or "core" (Ed's example shows "app/"; core files get
 *      "core/"). Only the file NAME is kept, never the package path:
 *      "core/CallerLocationProbeTest.kt:12", not
 *      "core/src/main/java/com/assistant/events/CallerLocationProbeTest.kt:12".
 *   6. Grouping state is PER DESTINATION STREAM. RuntimeLogger holds one
 *      Grouper per stream (main field log, execution chain, telemetry,
 *      heartbeat, field-test forensic); each stream re-headers
 *      independently. A location change in one stream never forces a
 *      header in another.
 *   7. Session headers ("=== SESSION START ===") and other raw writes
 *      bypass grouping entirely — they are not location lines.
 *
 * THREADING: Grouper is NOT thread-safe by itself. It is only ever used
 * under RuntimeLogger's @Synchronized monitor (every public entry point
 * there holds the object monitor), except for the rotation reset, which
 * runs on the single RuntimeLogIO thread and touches only the @Volatile
 * lastLocation field. The worst case of that benign race is one duplicate
 * or one missing header line — cosmetic only, never a lost message.
 *
 * PURE KOTLIN: this file imports nothing Android. The harness pins the
 * mapping and the grouping rule directly (no59 LogFormatTest); the
 * RuntimeLogger stays the only place that walks a real stack trace.
 */
object LogFormat {

    /**
     * Short location for one stack frame: "<module>/<File>.<ext>:<line>".
     *
     * The module mapping is the R-D3 core-package map RuntimeLogger
     * already owns (kept there, passed in here so this object stays
     * pure and pinnable): a class in a core package maps to "core",
     * everything else to "app". [fileName] is the frame's file name
     * (e.g. "AgilityContributor.kt"); [line] must be positive — a
     * non-positive line or a null file means the frame carries no real
     * source location and yields "unknown:0", exactly as the old
     * callerLocation() did.
     */
    fun shortLocation(
        className: String,
        fileName: String?,
        line: Int,
        corePackages: Array<String>
    ): String {
        if (fileName == null || line <= 0) return "unknown:0"
        val module = if (corePackages.any { className.startsWith(it) }) "core" else "app"
        return "$module/$fileName:$line"
    }

    /**
     * One location run's grouping state for a single destination stream.
     * See the header comment for the rules. [format] returns the header
     * plus the body when the location changed since the last call, and
     * just the body when it did not. [body] must already end with "\n"
     * (RuntimeLogger always passes a terminated line); the header is
     * emitted as its own terminated line in front of it.
     */
    class Grouper {
        @Volatile
        private var lastLocation: String? = null

        fun format(timestamp: String, location: String, body: String): String {
            return if (location == lastLocation) {
                body
            } else {
                lastLocation = location
                "$timestamp: $location:\n$body"
            }
        }

        /** Forget the run (log rotation: the new file starts clean). */
        fun reset() {
            lastLocation = null
        }
    }
}
