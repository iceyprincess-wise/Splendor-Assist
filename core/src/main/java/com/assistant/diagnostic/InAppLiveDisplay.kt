package com.assistant.diagnostic

/*
 * no59 — ITEM 4: the single switch that stops all in-app loggers.
 *
 * ED'S DECISION (his words, paraphrased faithfully): on a 4GB RAM phone
 * the app runs overlay + accessibility for the best gameplay, plus
 * eFootball 2027, plus logging to file every second — the loggers are
 * more active and responding than the engines, and the logging speed and
 * load on the RAM can kill engine effectiveness. File logging STAYS (it
 * is the proof) but must not become a problem. "You must stop all in app
 * loggers, only log to those file path it's enough."
 *
 * THE MECHANISM: one flag, default OFF, read by every periodic in-app
 * live display before it re-arms its timer. When the flag is false (the
 * shipped state) each display refreshes exactly ONCE — on open, or on
 * the user's explicit tap — and never polls again. File logging is
 * untouched by this flag: RuntimeLogger, LogMirror, the forensic logs
 * and every other file writer keep writing exactly as before.
 *
 * STOPPED (periodic in-app live displays — each site's re-post is gated
 * on liveRefreshAllowed() and therefore never re-arms):
 *   - DashboardInjector — 1s overlay refresh (bg thread builds 5
 *     snapshot strings every second + main-thread TextView set).
 *   - MainActivity hub — 3s refresh; the tick itself admits it builds
 *     "massive String/Map allocations on the Main Thread, triggering GC
 *     pauses that stutter eFootball 2027" (it was already cut 1s -> 3s).
 *   - ModMenuController status ticker — 1s overlay status line.
 *   - AgentHubActivity — 3s agent-core + heal-events refresh.
 *   - GameplayRoomActivity — 2s refresh; this one ALSO appended a line
 *     to gameplaylogs.txt on every cycle, so stopping it stops that
 *     write too (manual "Refresh Now" still refreshes + logs once).
 *   - (Phase I: the FutureRoomsActivity agent screen listed here was
 *     removed by No66-D44 — the room now only navigates to
 *     AgentHubActivity, which is gated above. No refresh exists there.)
 *
 * STAYS (not loggers — user-initiated one-shot views, no timer):
 *   - CrashInspectorActivity — opens a crash file when the user taps
 *     Prev/Next; bounded 256KB read, no polling.
 *   - AnalyticsTheaterActivity — loads once when the screen opens (on a
 *     background thread since no42); Save/Delete are user taps.
 *   - Goalkeeper / Interception / SmartAssist control rooms — one-shot
 *     status on create + a manual Refresh button + onResume; no timer
 *     was ever there, so there is nothing to stop.
 *
 * STAYS (not displays at all — file logging and engine health loops):
 *   - RuntimeLogger / LogMirror / forensic / deathwatch / heal-log /
 *     filter-report / TruthLogcat file writers (the proof Ed keeps).
 *   - DiagnosticSamplers (5s FPS / 15s net / 30s mem+thermal, file
 *     only, background thread), CrashForensics 10s snapshot monitor
 *     (file only), TruthLogcatEngine 500ms tail (file-to-file filter),
 *     InAppAgentCore 2s heal cycles, watchdog heartbeats.
 *
 * REVERSAL: the flag is a plain @Volatile var, not a const, so a future
 * round can re-enable live rooms with one assignment — but nothing in
 * production sets it true today, and no settings UI exposes it. Turning
 * it back on is Ed's call, not a default.
 */
object InAppLiveDisplay {

    @Volatile
    var liveRefreshEnabled: Boolean = false

    fun liveRefreshAllowed(): Boolean = liveRefreshEnabled
}
