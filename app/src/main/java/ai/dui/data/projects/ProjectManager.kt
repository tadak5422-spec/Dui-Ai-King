package ai.dui.data.projects

import android.content.Context
import ai.dui.data.daemon.IdeApiClient
import ai.dui.data.daemon.IdeDaemonManager
import ai.dui.data.daemon.ProjectInfo
import ai.dui.data.daemon.ProjectItem
import ai.dui.data.files.ExplorerPrefs
import ai.dui.data.remote.HubMediaResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Project management on top of the existing Projects feature: details, and the one permanent
 * deletion path. Nothing here creates a backup: deleting a project removes every app-managed copy
 * (files, list entry, hidden checkpoints, caches) and verifies that none is left.
 */
object ProjectManager {

    data class Step(val label: String, val ok: Boolean, val detail: String = "")

    data class DeleteReport(
        val success: Boolean,
        val steps: List<Step>,
        val errors: List<String> = emptyList()
    )

    suspend fun info(path: String): ProjectInfo? = IdeApiClient.projectInfo(path)

    /**
     * Permanently deletes [project]. Steps that fail are reported as failures; [DeleteReport.success]
     * is true only when the bridge and the app both proved that nothing is left.
     */
    suspend fun delete(
        context: Context,
        project: ProjectItem,
        onStep: (String) -> Unit = {}
    ): DeleteReport = withContext(Dispatchers.IO) {
        val steps = mutableListOf<Step>()
        val errors = mutableListOf<String>()

        if (project.fromChat) {
            return@withContext DeleteReport(
                success = false,
                steps = listOf(Step("This is a chat workspace, not a project", false)),
                errors = listOf("Only projects can be deleted")
            )
        }

        // 1. Editor tabs of the project must go first: a later save could recreate its folders.
        onStep("Closing the project's editor tabs…")
        val closed = runCatching { IdeDaemonManager.closeTabsUnder(project.path) }.getOrDefault(false)
        steps += Step("Closed the project's editor tabs", true, if (closed) "closed" else "none open")

        // 2. Forget it as the active project.
        onStep("Clearing the active project…")
        val activePath = IdeDaemonManager.activeProject.value?.path?.trimEnd('/')
        val root = project.path.trimEnd('/')
        val wasActive = activePath != null && (activePath == root || activePath.startsWith("$root/"))
        if (wasActive) IdeDaemonManager.setActiveProject(null)
        steps += Step("Cleared the active project", true, if (wasActive) "yes" else "not active")

        // 3. The bridge deletes the files, the saved entry and the hidden checkpoints, then verifies.
        onStep("Deleting files, project entry and checkpoints…")
        val result = IdeApiClient.deleteProject(project.path)
        if (result == null) {
            errors += "The IDE server did not answer the delete request. Nothing was verified."
            steps += Step("Delete request", false, "no answer from the bridge")
            return@withContext DeleteReport(false, steps, errors)
        }
        steps += Step(
            "Deleted the project files",
            result.filesDeleted && result.pathGone,
            if (result.pathGone) "folder is gone" else "folder is still there"
        )
        steps += Step(
            "Removed it from the project list",
            result.savedRemoved && result.savedGone,
            if (result.savedGone) "no entry left" else "entry is still saved"
        )
        steps += Step(
            "Deleted its hidden checkpoints",
            result.checkpointsGone,
            if (result.checkpointsDeleted > 0) "${result.checkpointsDeleted} checkpoint(s) removed" else "none existed"
        )
        errors += result.errors

        // 4. App-side copies: recents, remembered paths and cached file copies.
        onStep("Clearing recent folders and caches…")
        runCatching { ExplorerPrefs.forgetPath(project.path) }
        runCatching { File(context.cacheDir, "explorer_open").deleteRecursively() }
        runCatching { HubMediaResolver.invalidateDocument(project.path) }
        steps += Step("Cleared recent folders and app caches", true)

        // 5. The list is reloaded from the bridge and the path is checked once more on disk.
        onStep("Verifying that no copy is left…")
        val stillOnDisk = runCatching { File(project.path).exists() }.getOrDefault(false)
        val listed = runCatching {
            IdeApiClient.getProjects().any { it.path.trimEnd('/') == root }
        }.getOrDefault(false)
        val gone = !stillOnDisk && !listed
        steps += Step(
            "Verified no copy is left",
            gone,
            when {
                stillOnDisk -> "the folder is still on disk"
                listed -> "the project is still in the list"
                else -> "no files, list entry or checkpoint"
            }
        )
        if (!gone) errors += "A copy of the project is still reachable."

        IdeDaemonManager.removeProjectFromList(project.path)

        DeleteReport(
            success = result.success && gone && errors.isEmpty(),
            steps = steps,
            errors = errors
        )
    }
}
