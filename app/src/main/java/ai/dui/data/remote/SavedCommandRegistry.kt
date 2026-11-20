package ai.dui.data.remote

import ai.dui.domain.model.McpServerState

/**
 * One saved skill as shown by the `/skill:` picker. Only the short [id] travels in the chat text; the rest is kept
 * here so [PromptExpansion] can build the real instruction when the message is sent.
 */
data class SavedSkillInfo(
    val id: String,
    val name: String,
    val description: String = "",
    val path: String = "",
    val kind: String = "skill",
    val instruction: String = ""
)

/**
 * In-memory copy of the items shown by the `/skill:` and `/mcp:` pickers.
 *
 * The chat text only carries a short token such as `/skill:android-dev`. The actual instruction for the model is
 * built when the message is sent ([PromptExpansion.expand]), so the picked item is resolved from here. The picker
 * fills this registry every time it is opened; a stale entry simply means the item was removed since then, and the
 * token is left untouched instead of sending a guide to a skill that no longer exists.
 */
object SavedCommandRegistry {
    @Volatile private var skills: List<SavedSkillInfo> = emptyList()
    @Volatile private var servers: List<McpServerState> = emptyList()

    fun rememberSkills(items: List<SavedSkillInfo>) { skills = items }
    fun rememberServers(items: List<McpServerState>) { servers = items }

    fun skill(id: String): SavedSkillInfo? =
        skills.firstOrNull { it.id.equals(id, ignoreCase = true) || it.name.equals(id, ignoreCase = true) }

    fun server(name: String): McpServerState? =
        servers.firstOrNull { it.name.equals(name, ignoreCase = true) }
}
