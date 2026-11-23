package ai.dui.data.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import ai.dui.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

private const val TAG = "AppUpdateManager"

/**
 * This fork's own release channel. The app must never look at the original developer's repository for updates:
 * version.json and every APK are published by the fork's CI (`.github/workflows/release.yml`).
 */
private const val UPDATE_REPO = "tadak5422-spec/Dui-Ai-King"
/**
 * version.json is published as a release asset by the release workflow with the SHA-256 of the APK built in that
 * same job. A branch copy cannot be trusted for integrity data, so the app reads the signed-by-GitHub asset instead.
 */
private const val DEFAULT_VERSION_URL = "https://github.com/$UPDATE_REPO/releases/latest/download/version.json"

// the updater's prefs (UpdateCheckWorker keeps its "last notified" version there too)
private const val UPDATE_PREFS = "app_update_prefs"
private const val KEY_BETA_UPDATES = "beta_updates"

/**
 * "Get beta updates" (Settings > About > Software Updates). Saved in the app's data, so it stays through updates.
 * Default off: this fork's release channel is stable unless the user opts into a "beta" block in version.json.
 */
object BetaUpdates {
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE).getBoolean(KEY_BETA_UPDATES, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_BETA_UPDATES, enabled).apply()
    }
}

data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val currentVersionCode: Int,
    val currentVersionName: String,
    val packageName: String,
    val downloadUrl: String,
    val changelog: List<String>,
    val isCritical: Boolean = false,
    /** Mandatory SHA-256 of the APK, published by CI with the release; verified before the installer is opened. */
    val sha256: String
)

sealed class DownloadState {
    object Idle : DownloadState()
    data class Downloading(val progress: Float, val downloadedBytes: Long, val totalBytes: Long) : DownloadState()
    data class Completed(val file: File) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

class AppUpdateManager(private val context: Context) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()


    /**
     * Checks if a new version is available by fetching centralized version.json from GitHub.
     */
    suspend fun checkForUpdates(
        versionJsonUrl: String = DEFAULT_VERSION_URL,
        forceCheck: Boolean = false
    ): Result<AppUpdateInfo?> = withContext(Dispatchers.IO) {
        try {
            if (!isAllowedVersionUrl(versionJsonUrl)) {
                return@withContext Result.failure(Exception("Refusing update metadata from outside this fork's release channel"))
            }
            val request = Request.Builder()
                .url(versionJsonUrl)
                .header("User-Agent", "DuiAI-Updater/${BuildConfig.VERSION_NAME}")
                .header("Cache-Control", "no-cache")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!isAllowedFinalUrl(response.request.url.toString())) {
                response.close()
                return@withContext Result.failure(Exception("Update metadata was redirected outside this fork's release channel"))
            }
            if (!response.isSuccessful) {
                if (response.code == 404) {
                    // No release with a signed version.json yet: that is "no update", not a security failure.
                    return@withContext Result.success(null)
                }
                val errorMsg = when (response.code) {
                    403 -> "GitHub rate limit exceeded or access forbidden (HTTP 403)."
                    else -> "Failed to fetch version info: HTTP ${response.code} (${response.message})"
                }
                return@withContext Result.failure(Exception(errorMsg))
            }

            val rawJson = response.body?.string() ?: return@withContext Result.failure(Exception("Empty version response from server"))

            val json = JSONObject(rawJson)
            val release = releaseFor(json)
            val remoteVersionCode = release.code
            val remoteVersionName = release.name
            val minSupportedCode = json.optInt("min_supported_version_code", 0)
            val changelog = release.changelog
            val isCritical = json.optBoolean("is_critical", false) || (BuildConfig.VERSION_CODE < minSupportedCode)

            // Integrity data is mandatory: a release without a valid SHA-256 is never offered as an update.
            val sha256 = release.sha256
            if (!isValidSha256(sha256)) {
                return@withContext Result.failure(Exception("This release has no valid SHA-256 in version.json; update refused."))
            }

            // Each release (tag v<version>) has the app as DuiAI-v<version>.apk (scripts/build_all_apks.sh).
            val downloadUrl = release.downloadUrl
                ?: "https://github.com/$UPDATE_REPO/releases/download/v$remoteVersionName/DuiAI-v$remoteVersionName.apk"

            if (remoteVersionCode > BuildConfig.VERSION_CODE && downloadUrl.isNotBlank()) {
                if (!isAllowedUpdateUrl(downloadUrl)) {
                    return@withContext Result.failure(Exception("version.json points at an update URL outside this fork's release channel"))
                }
                // Verify that the APK asset is actually published and downloadable on GitHub
                val probeRequest = Request.Builder()
                    .url(downloadUrl)
                    .head()
                    .header("User-Agent", "DuiAI-Updater/${BuildConfig.VERSION_NAME}")
                    .build()

                val isAvailable = try {
                    httpClient.newCall(probeRequest).execute().use { probeResponse ->
                        (probeResponse.isSuccessful || probeResponse.isRedirect) &&
                            isAllowedFinalUrl(probeResponse.request.url.toString())
                    }
                } catch (_: Exception) {
                    false
                }

                if (!isAvailable) {
                    // Release is not yet fully published or APK is still building on CI
                    return@withContext Result.success(null)
                }

                Result.success(
                    AppUpdateInfo(
                        versionCode = remoteVersionCode,
                        versionName = remoteVersionName,
                        currentVersionCode = BuildConfig.VERSION_CODE,
                        currentVersionName = BuildConfig.VERSION_NAME,
                        packageName = context.packageName,
                        downloadUrl = downloadUrl,
                        changelog = changelog,
                        isCritical = isCritical,
                        sha256 = sha256
                    )
                )
            } else {
                Result.success(null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check for updates: ${e.message}", e)
            Result.failure(e)
        }
    }

    /** One release in version.json: the stable one (top level) or the beta ("beta" block). */
    private class RemoteRelease(
        val code: Int,
        val name: String,
        val changelog: List<String>,
        val sha256: String,
        val downloadUrl: String?
    )

    /**
     * The release this install follows in version.json. Without beta updates it reads only the top level, as every
     * installed app always has, so it never sees a beta. With them it takes the newer (higher version_code) of the
     * stable release and the "beta" block. A beta without a valid SHA-256 is ignored: no release is ever offered
     * without integrity data.
     */
    private fun releaseFor(json: JSONObject): RemoteRelease {
        val stable = RemoteRelease(
            code = json.optInt("version_code", 0),
            name = json.optString("version_name", "1.0.0"),
            changelog = changelogOf(json),
            sha256 = json.optString("sha256").trim().lowercase(),
            downloadUrl = json.optString("download_url").trim().ifBlank { null }
        )
        if (!BetaUpdates.isEnabled(context)) return stable
        val beta = json.optJSONObject("beta") ?: return stable
        val candidate = RemoteRelease(
            code = beta.optInt("version_code", 0),
            name = beta.optString("version_name", ""),
            changelog = changelogOf(beta),
            sha256 = beta.optString("sha256").trim().lowercase(),
            downloadUrl = beta.optString("download_url").trim().ifBlank { null }
        )
        return if (candidate.code > stable.code && candidate.name.isNotBlank() && isValidSha256(candidate.sha256)) candidate else stable
    }

    private fun changelogOf(json: JSONObject): List<String> = when (val raw = json.opt("changelog")) {
        is org.json.JSONArray -> (0 until raw.length()).map { raw.optString(it) }
        is String -> listOf(raw)
        else -> listOf("General improvements and bug fixes.")
    }

    private fun isValidSha256(value: String): Boolean = value.matches(Regex("^[0-9a-f]{64}$"))

    /**
     * Downloads the APK file to cache directory with live progress updates.
     */
    suspend fun downloadApk(
        downloadUrl: String,
        expectedSha256: String,
        onProgress: (Float, Long, Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            if (!isAllowedUpdateUrl(downloadUrl)) {
                return@withContext Result.failure(Exception("Refusing an update from outside this fork's release channel"))
            }
            if (!isValidSha256(expectedSha256)) {
                return@withContext Result.failure(Exception("Refusing an update without a valid SHA-256"))
            }
            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "DuiAI-Updater/${BuildConfig.VERSION_NAME}")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!isAllowedFinalUrl(response.request.url.toString())) {
                response.close()
                return@withContext Result.failure(Exception("The update download was redirected outside this fork's release channel"))
            }
            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Download failed: HTTP ${response.code}"))
            }

            val body = response.body ?: return@withContext Result.failure(Exception("Empty download body"))
            val totalBytes = body.contentLength()

            val targetDir = context.externalCacheDir ?: context.cacheDir
            val targetFile = File(targetDir, "DuiAI_update_${System.currentTimeMillis()}.apk")

            body.byteStream().use { input ->
                targetFile.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var bytesRead: Int
                    var downloadedBytes = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead

                        val progress = if (totalBytes > 0) {
                            (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        withContext(Dispatchers.Main) {
                            onProgress(progress, downloadedBytes, totalBytes)
                        }
                    }
                    output.flush()
                }
            }

            // Mandatory byte-for-byte check, always before the package installer or the signer check.
            val actual = sha256(targetFile)
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                targetFile.delete()
                return@withContext Result.failure(Exception("The downloaded update does not match the SHA-256 published with it."))
            }

            val expectedSigner = ai.dui.data.security.ApkSignatureVerifier.installedSignerSha256(context)
            val actualSigner = ai.dui.data.security.ApkSignatureVerifier.signerSha256(context, targetFile)
            val archivePackage = ai.dui.data.security.ApkSignatureVerifier.archivePackageName(context, targetFile)
            val archiveVersion = ai.dui.data.security.ApkSignatureVerifier.archiveVersionCode(context, targetFile)
            val installedVersion = ai.dui.data.security.ApkSignatureVerifier.installedVersionCode(context)
            if (archivePackage != context.packageName ||
                expectedSigner == null || actualSigner == null ||
                !actualSigner.equals(expectedSigner, ignoreCase = true)
            ) {
                targetFile.delete()
                return@withContext Result.failure(
                    Exception("The downloaded file is not a correctly signed ${context.packageName} update.")
                )
            }
            if (archiveVersion == 0L || archiveVersion < installedVersion) {
                targetFile.delete()
                return@withContext Result.failure(Exception("The downloaded update is not newer than the installed app."))
            }

            Result.success(targetFile)
        } catch (e: Exception) {
            Log.e(TAG, "Download APK error: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Launches the system Package Installer using FileProvider.
     */
    fun installApk(apkFile: File): Boolean {
        return try {
            val authority = "${context.packageName}.fileprovider"
            val apkUri = FileProvider.getUriForFile(context, authority, apkFile)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // Android 8.0+ Unknown sources permission check
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    val manageIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(manageIntent)
                    return false
                }
            }

            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer: ${e.message}", e)
            false
        }
    }

    /** Metadata may only be version.json published in this fork's GitHub releases, never a branch copy. */
    private fun isAllowedVersionUrl(url: String): Boolean {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.userInfo != null) return false
        if (uri.host?.lowercase() != "github.com") return false
        val path = uri.path.orEmpty()
        return path == "/$UPDATE_REPO/releases/latest/download/version.json" ||
            (path.startsWith("/$UPDATE_REPO/releases/download/") && path.endsWith("/version.json"))
    }

    /** Only this fork's GitHub release assets may ever be downloaded as an app update. */
    private fun isAllowedUpdateUrl(url: String): Boolean {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.userInfo != null) return false
        return uri.host?.lowercase() == "github.com" &&
            uri.path.orEmpty().startsWith("/$UPDATE_REPO/releases/download/")
    }

    /** OkHttp follows redirects automatically; verify the host actually fetched is still this fork's release channel. */
    private fun isAllowedFinalUrl(url: String): Boolean {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.userInfo != null) return false
        val host = uri.host?.lowercase() ?: return false
        val path = uri.path.orEmpty()
        return when (host) {
            "github.com" -> path.startsWith("/$UPDATE_REPO/releases/")
            // GitHub serves release assets from this CDN after redirecting the github.com download URL.
            "objects.githubusercontent.com" -> true
            else -> false
        }
    }

    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(1024 * 1024).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
