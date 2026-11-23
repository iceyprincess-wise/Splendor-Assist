package com.assistant

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Launch splash (UI-2) — wires the orphaned activity_welcome layout
 * (File No 95, no60 premium re-touch) as the launch screen.
 *
 * Flow: the app first shows the logo page loading, then proceeds into
 * the app (MainActivity dashboard). The update page appears ONLY when
 * there is an update — that is the updater piece's job, not this one.
 *
 * UPDATER HOOK (Piece 3, implemented): maybeShowUpdatePage() below is
 * the single call site where the update check runs. The check starts
 * in onCreate (off the main thread) and runs during the 1200ms logo
 * loading. When an update is CONFIRMED available it starts
 * UpdateActivity (the splash does not proceed to MainActivity); when
 * the check fails or no update is available the splash proceeds as
 * normal (fail open — a broken check must never lock Ed out of the app).
 *
 * The splash finishes itself after starting MainActivity (or
 * UpdateActivity), so it never re-appears on Back. A `proceeded`
 * guard stops a double start if the activity is recreated while the
 * delay is pending; a `navigated` guard stops the check callback
 * navigating twice.
 */
class SplashActivity : AppCompatActivity() {

    private val splashHandler = Handler(Looper.getMainLooper())
    private var proceeded = false

    // Piece 3: the update-check result. The check starts in onCreate
    // (off the main thread) so it runs during the 1200ms logo loading;
    // maybeShowUpdatePage() uses the result at proceed time, or the
    // callback navigates when the check finishes after proceed().
    private var checkResult: UpdateChecker.Result? = null
    private var checkDone = false
    private var navigated = false

    private val proceedRunnable = Runnable { proceed() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Updater cleanup piece (Ed's requirement): delete the
        // downloaded APK after the install, so APKs do not pile up in
        // storage. Runs on every launch, including the auto-restart
        // after an install. The helper refuses to delete while a
        // download is pending or an install has not completed yet, so
        // this cannot break an in-flight download or install.
        UpdateActivity.deleteDownloadedApk(this)
        // UI-2: edge-to-edge (targetSdk 36 enforces it) — draw behind
        // the system bars; the insets are applied to the content frame
        // below so the logo and loading text never sit under a system bar.
        enableEdgeToEdge()
        setContentView(com.assistant.overlay.R.layout.activity_welcome)
        val content = findViewById<android.view.View>(android.R.id.content)
        if (content != null) {
            ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        // Piece 3: start the update check now (off the main thread) so
        // it runs during the 1200ms logo loading. When it completes, if
        // proceed() has already run, navigate then; otherwise
        // maybeShowUpdatePage() uses the stored result. A failed check
        // returns null — fail open.
        UpdateChecker.checkForUpdate(this) { result ->
            checkResult = result
            checkDone = true
            if (proceeded && !navigated) navigateForUpdate(result)
        }
        // Show the logo page loading, then proceed into the app.
        splashHandler.postDelayed(proceedRunnable, 1200L)
    }

    private fun proceed() {
        if (proceeded) return
        proceeded = true
        // Piece 3: the update check runs inside maybeShowUpdatePage().
        // It returns true when the update flow owns navigation (the
        // update page, or the check callback entering the app), so the
        // splash does not start MainActivity here.
        if (maybeShowUpdatePage()) return
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    /**
     * Piece 3 (implements the UI-2 UPDATER HOOK). Returns true when
     * the update flow owns navigation — the splash must not proceed to
     * MainActivity in that case. When the check has already finished
     * during the 1200ms, navigate now; when it is still running,
     * return true and let the check callback navigate when it
     * completes. A failed check returns null and the callback enters
     * MainActivity (fail open — a broken check never locks Ed out).
     */
    private fun maybeShowUpdatePage(): Boolean {
        if (checkDone) {
            navigateForUpdate(checkResult)
            return true
        }
        // Check still running: the onCreate callback navigates when it
        // completes. Return true so proceed() does not start MainActivity.
        return true
    }

    /**
     * Piece 3: navigates once. A confirmed update (non-null result)
     * starts UpdateActivity with the version + notes; no update or a
     * failed check (null) enters MainActivity (fail open). Finishes
     * the splash either way.
     */
    private fun navigateForUpdate(result: UpdateChecker.Result?) {
        if (navigated) return
        navigated = true
        if (isFinishing || isDestroyed) return
        if (result != null) {
            val intent = Intent(this, UpdateActivity::class.java)
            intent.putExtra(UpdateActivity.EXTRA_LATEST_VERSION_CODE, result.latestVersionCode)
            intent.putExtra(UpdateActivity.EXTRA_RELEASE_NOTES, result.releaseNotes)
            intent.putExtra(UpdateActivity.EXTRA_APK_DOWNLOAD_URL, result.apkDownloadUrl)
            startActivity(intent)
        } else {
            startActivity(Intent(this, MainActivity::class.java))
        }
        finish()
    }

    override fun onDestroy() {
        splashHandler.removeCallbacks(proceedRunnable)
        super.onDestroy()
    }
}
