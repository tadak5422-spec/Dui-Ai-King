package ai.dui.data.security

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.File
import java.security.MessageDigest

/**
 * Reads the signing certificate of an APK file without installing it, so a download can be checked against a pin
 * (X11) or against the signature of the installed app (updates) before the system installer is opened.
 */
object ApkSignatureVerifier {

    fun signerSha256(context: Context, apk: File): String? =
        runCatching { context.packageManager.getPackageArchiveInfo(apk.absolutePath, certFlags()) }
            .getOrNull()?.let(::currentSignerSha256)

    fun installedSignerSha256(context: Context): String? =
        runCatching { context.packageManager.getPackageInfo(context.packageName, certFlags()) }
            .getOrNull()?.let(::currentSignerSha256)

    fun archivePackageName(context: Context, apk: File): String? =
        runCatching { context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)?.packageName }
            .getOrNull()

    /** Version code of the APK file (0 when it cannot be read), for blocking downgrades. */
    @Suppress("DEPRECATION")
    fun archiveVersionCode(context: Context, apk: File): Long =
        runCatching { context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)?.versionCode?.toLong() }
            .getOrDefault(0L)

    @Suppress("DEPRECATION")
    fun installedVersionCode(context: Context): Long =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0)?.versionCode?.toLong() }
            .getOrDefault(0L)

    @Suppress("DEPRECATION")
    private fun certFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES
        else PackageManager.GET_SIGNATURES

    /** The signer in use now: for a rotated key, the last entry of the signing certificate history. */
    @Suppress("DEPRECATION")
    private fun currentSignerSha256(info: PackageInfo): String? {
        val signers: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signing = info.signingInfo ?: return null
            if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        } else {
            info.signatures
        }
        val signer = signers?.lastOrNull() ?: return null
        return MessageDigest.getInstance("SHA-256")
            .digest(signer.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
