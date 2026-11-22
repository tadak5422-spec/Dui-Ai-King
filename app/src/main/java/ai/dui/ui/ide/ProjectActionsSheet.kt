package ai.dui.ui.ide

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.dui.data.daemon.IdeApiClient
import ai.dui.data.daemon.ProjectApkInfo
import ai.dui.data.daemon.ProjectInfo
import ai.dui.data.daemon.ProjectItem
import ai.dui.data.files.ApkInstaller
import ai.dui.data.projects.ProjectManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The actions of one project of the existing Projects list: details, APK install, files, and the
 * one permanent deletion flow. Nothing here is a second Projects section: it is the menu behind
 * the list that already exists.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectActionsSheet(
    project: ProjectItem,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onBrowse: () -> Unit,
    onChanged: () -> Unit,
    onDeleted: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var info by remember(project.path) { mutableStateOf<ProjectInfo?>(null) }
    var loading by remember(project.path) { mutableStateOf(true) }
    var loadError by remember(project.path) { mutableStateOf<String?>(null) }

    var confirmDelete by remember { mutableStateOf(false) }
    var typedName by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf(false) }
    var progressLabel by remember { mutableStateOf("") }
    var report by remember { mutableStateOf<ProjectManager.DeleteReport?>(null) }

    fun loadInfo() {
        loading = true
        loadError = null
        scope.launch {
            val res = ProjectManager.info(project.path)
            info = res
            loading = false
            if (res == null) loadError = "The IDE server could not describe this project."
        }
    }

    LaunchedEffect(project.path) { loadInfo() }

    ModalBottomSheet(
        onDismissRequest = { if (!deleting) onDismiss() },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (project.isApk) Icons.Outlined.InstallMobile else Icons.Outlined.FolderSpecial,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        project.name,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        project.path,
                        fontSize = 11.5.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            HorizontalDivider()
            Spacer(Modifier.height(14.dp))

            if (report != null) {
                val r = report!!
                Text(
                    if (r.success) "Project deleted permanently" else "Deletion did not finish",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (r.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(8.dp))
                r.steps.forEach { step -> StepRow(step.label, step.ok, step.detail) }
                if (r.errors.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        r.errors.joinToString("\n"),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (r.success) {
                        Button(onClick = onDeleted, modifier = Modifier.weight(1f)) { Text("Done") }
                    } else {
                        OutlinedButton(onClick = { report = null }, modifier = Modifier.weight(1f)) { Text("Try again") }
                        Button(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Close") }
                    }
                }
                return@Column
            }

            if (deleting) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(progressLabel, fontSize = 13.sp)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Deleting for good: no trash, no backup and no recovery copy is created.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                return@Column
            }

            if (loading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Reading project details…", fontSize = 13.sp)
                }
            } else if (loadError != null) {
                Text(loadError!!, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { loadInfo() }) { Text("Retry") }
            } else {
                val i = info
                if (i != null) {
                    InfoRow("Type", if (i.kind == ProjectItem.KIND_APK) "APK" else "Source project")
                    InfoRow("Size", formatBytes(i.size) + if (i.partial) " (still counting)" else "")
                    if (i.kind != ProjectItem.KIND_APK) InfoRow("Files", "${i.fileCount}")
                    if (i.lastModified > 0) InfoRow("Last modified", formatDate(i.lastModified))
                    if (i.gitBranch.isNotBlank()) InfoRow("Git branch", i.gitBranch)
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Actions", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))

            ActionRow(Icons.Outlined.Code, "Open in IDE", "Edit the project's code") {
                onOpen()
            }
            ActionRow(Icons.Outlined.FolderOpen, "Browse files", "See and manage the project's files") {
                onBrowse()
            }

            val apks = info?.apks.orEmpty()
            if (apks.isEmpty()) {
                ActionRow(
                    Icons.Outlined.InstallMobile,
                    "Install APK",
                    if (project.isApk) "The APK is missing" else "No APK has been built in this project yet",
                    enabled = false
                ) {}
            } else {
                apks.forEach { apk ->
                    ApkRow(apk) {
                        val error = ApkInstaller.install(context, apk.path)
                        if (error != null) Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                    }
                }
            }

            if (project.isCustom) {
                ActionRow(Icons.Outlined.Close, "Remove from list", "Keep the files, only hide it from Projects") {
                    scope.launch {
                        IdeApiClient.removeSavedProject(project.path)
                        onChanged()
                        onDismiss()
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            HorizontalDivider()
            Spacer(Modifier.height(6.dp))

            ActionRow(
                Icons.Outlined.DeleteForever,
                "Delete permanently",
                "Files, APK, checkpoints and caches: nothing is kept",
                danger = true
            ) {
                typedName = ""
                confirmDelete = true
            }

            Spacer(Modifier.height(10.dp))
            Text(
                "Private to this app: the project list and checkpoints live in DuiAI's own storage and the IDE server only answers on this phone. Deletion never uploads or backs anything up.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Outlined.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete permanently?") },
            text = {
                Column {
                    Text(
                        "This deletes \"${project.name}\" and everything in it: source code, APK, files, its project list entry and its hidden checkpoints.",
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "There is no trash, no backup and no way to restore it from the app.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Backup ZIPs you exported yourself (for example in Downloads) are your files: the app does not touch them.",
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Type the project name to confirm:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = typedName,
                        onValueChange = { typedName = it },
                        singleLine = true,
                        placeholder = { Text(project.name, fontSize = 13.sp) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = typedName.trim() == project.name.trim(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        confirmDelete = false
                        deleting = true
                        progressLabel = "Starting…"
                        scope.launch {
                            val res = ProjectManager.delete(context, project) { step -> progressLabel = step }
                            deleting = false
                            report = res
                        }
                    }
                ) { Text("Delete permanently") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                danger -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.primary
            },
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = when {
                    !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    danger -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )
            Text(subtitle, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}

@Composable
private fun ApkRow(apk: ProjectApkInfo, onInstall: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Outlined.Android, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(apk.name, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                formatBytes(apk.size) + if (apk.modified > 0) " · ${formatDate(apk.modified)}" else "",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = onInstall) { Text("Install") }
    }
}

@Composable
private fun StepRow(label: String, ok: Boolean, detail: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            imageVector = if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.Cancel,
            contentDescription = null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(17.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(label, fontSize = 12.5.sp)
            if (detail.isNotBlank()) {
                Text(detail, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "${bytes} ${units[unit]}"
    else String.format(Locale.US, "%.1f %s", value, units[unit])
}

private fun formatDate(millis: Long): String =
    SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(millis))
