package com.assistant

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app update check (updater Piece 3).
 *
 * Reads the app's own versionCode via PackageManager, fetches the
 * latest GitHub release
 * (api.github.com/repos/iceyprincess-wise/Splendor-Assist/releases/latest)
 * and parses the versionCode from the release tag (the tag is "vN",
 * the versionCode is N). An update is available only when the latest
 * versionCode is GREATER than the installed one.
 *
 * FAIL OPEN: any failure — network error, timeout, non-200 response,
 * bad JSON, a tag that is not "vN", or an unreadable installed
 * versionCode — returns null, which the caller treats as "no update"
 * and enters the app normally. A broken check must never lock Ed out.
 *
 * No networking library is in the dependencies (no OkHttp/Retrofit),
 * so this uses HttpURLConnection. The check runs off the main thread;
 * the callback runs on the main thread. Timeouts are short (5s) so a
 * slow network cannot hang the splash.
 */
object UpdateChecker {

    /**
     * An update is confirmed: the latest release's versionCode, its
     * notes, and the APK download URL (Piece 4: the asset URL from the
     * release's assets array — the app-release.apk
     * browser_download_url — falling back to the constructed release
     * download URL when the assets array does not name it).
     */
    data class Result(
        val latestVersionCode: Long,
        val releaseNotes: String?,
        val apkDownloadUrl: String?
    )

    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/iceyprincess-wise/Splendor-Assist/releases/latest"
    private const val CONNECT_TIMEOUT_MS = 5000
    private const val READ_TIMEOUT_MS = 5000

    private val mainHandler = Handler(Looper.getMainLooper())

    /** The installed versionCode, or -1 when it cannot be read. */
    fun installedVersionCode(context: Context): Long {
        return try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName, PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        } catch (e: Exception) {
            -1L
        }
    }

    /**
     * Runs the check off the main thread; [onResult] runs on the main
     * thread with the confirmed update, or null when there is no update
     * or the check failed (fail open — the caller enters the app).
     */
    fun checkForUpdate(context: Context, onResult: (Result?) -> Unit) {
        val appContext = context.applicationContext
        Thread {
            val result = runCatching { fetchLatest(appContext) }.getOrNull()
            mainHandler.post { onResult(result) }
        }.start()
    }

    /** Returns the confirmed update, or null (no update / any failure). */
    private fun fetchLatest(context: Context): Result? {
        val installed = installedVersionCode(context)
        if (installed < 0) return null
        val conn = (URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            // GitHub's API requires a User-Agent; without it it 403s.
            setRequestProperty("User-Agent", "Splendor-Assist-Updater")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            // Tag is "vN" — the versionCode is N.
            val latest = json.optString("tag_name", "").removePrefix("v").toLongOrNull()
                ?: return null
            if (latest <= installed) return null
            val notes = json.optString("body", "").ifBlank { null }
            // APK download URL (Piece 4): the app-release.apk asset's
            // browser_download_url; fall back to the constructed
            // release download URL when the assets array does not
            // name it (the CI always attaches app-release.apk).
            val apkUrl = findApkAssetUrl(json)
                ?: "https://github.com/iceyprincess-wise/Splendor-Assist" +
                    "/releases/download/v$latest/app-release.apk"
            return Result(latest, notes, apkUrl)
        } finally {
            conn.disconnect()
        }
    }

    /** The app-release.apk asset's browser_download_url, or null. */
    private fun findApkAssetUrl(json: JSONObject): String? {
        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.optString("name") == "app-release.apk") {
                return asset.optString("browser_download_url").ifBlank { null }
            }
        }
        return null
    }
}
