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
private const val UPDATE_BRANCH = "standalone"
private const val DEFAULT_VERSION_URL = "https://raw.githubusercontent.com/$UPDATE_REPO/$UPDATE_BRANCH/version.json"

data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val currentVersionCode: Int,
    val currentVersionName: String,
    val packageName: String,
    val downloadUrl: String,
    val changelog: List<String>,
    val isCritical: Boolean = false,
    /** SHA-256 of the APK when version.json publishes one; verified before the installer is opened. */
    val sha256: String? = null
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
            val request = Request.Builder()
                .url(versionJsonUrl)
                .header("User-Agent", "DuiAI-Updater/${BuildConfig.VERSION_NAME}")
                .header("Cache-Control", "no-cache")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val errorMsg = when (response.code) {
                    404 -> "version.json not found on GitHub repository (HTTP 404). Please ensure version.json is pushed to the repository."
                    403 -> "GitHub rate limit exceeded or access forbidden (HTTP 403)."
                    else -> "Failed to fetch version info: HTTP ${response.code} (${response.message})"
                }
                return@withContext Result.failure(Exception(errorMsg))
            }

            val rawJson = response.body?.string() ?: return@withContext Result.failure(Exception("Empty version response from server"))

            val json = JSONObject(rawJson)
            val remoteVersionCode = json.optInt("version_code", 0)
            val remoteVersionName = json.optString("version_name", "1.0.0")
            val minSupportedCode = json.optInt("min_supported_version_code", 0)
            val changelog = when (val raw = json.opt("changelog")) {
                is org.json.JSONArray -> (0 until raw.length()).map { raw.optString(it) }
                is String -> listOf(raw)
                else -> listOf("General improvements and bug fixes.")
            }
            val isCritical = json.optBoolean("is_critical", false) || (BuildConfig.VERSION_CODE < minSupportedCode)

            // Each release (tag v<version>) has the app as DuiAI-v<version>.apk (scripts/build_all_apks.sh).
            // version.json may also name an explicit asset and its SHA-256; both are checked below.
            val downloadUrl = json.optString("download_url").trim().ifBlank {
                "https://github.com/$UPDATE_REPO/releases/download/v$remoteVersionName/DuiAI-v$remoteVersionName.apk"
            }
            val sha256 = json.optString("sha256").trim().lowercase().takeIf { it.matches(Regex("^[0-9a-f]{64}$")) }

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
                        probeResponse.isSuccessful || probeResponse.isRedirect
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

    /**
     * Downloads the APK file to cache directory with live progress updates.
     */
    suspend fun downloadApk(
        downloadUrl: String,
        expectedSha256: String? = null,
        onProgress: (Float, Long, Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            if (!isAllowedUpdateUrl(downloadUrl)) {
                return@withContext Result.failure(Exception("Refusing an update from outside this fork's release channel"))
            }
            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "DuiAI-Updater/${BuildConfig.VERSION_NAME}")
                .build()

            val response = httpClient.newCall(request).execute()
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

            // An APK published with a SHA-256 is checked byte for byte before the signature is even looked at.
            if (!expectedSha256.isNullOrBlank()) {
                val actual = sha256(targetFile)
                if (!actual.equals(expectedSha256, ignoreCase = true)) {
                    targetFile.delete()
                    return@withContext Result.failure(Exception("The downloaded update does not match the SHA-256 published with it."))
                }
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

    /** Only this fork's GitHub release assets may ever be downloaded as an app update. */
    private fun isAllowedUpdateUrl(url: String): Boolean {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        if (uri.userInfo != null) return false
        val host = uri.host?.lowercase() ?: return false
        return when (host) {
            "github.com" -> uri.path.orEmpty().startsWith("/$UPDATE_REPO/releases/")
            // GitHub redirects release downloads here; the initial URL above is the one we authorise.
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
