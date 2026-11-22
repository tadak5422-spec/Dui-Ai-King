package ai.dui.data.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * Opens Android's own package installer for an APK of a project. The app never installs by itself:
 * the system shows its confirmation screen, exactly like a file manager handing over an APK.
 */
object ApkInstaller {

    /** Returns null when the installer was opened, or a message explaining what is missing. */
    fun install(context: Context, path: String): String? {
        val file = File(path)
        if (!file.isFile) return "APK not found: $path"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return "Allow DuiAI to install apps, then tap Install again."
        }

        return runCatching {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            null
        }.getOrElse { e ->
            e.message ?: "Could not open the installer"
        }
    }
}
