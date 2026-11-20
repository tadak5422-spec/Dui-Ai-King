package ai.dui.data.remote

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray

/** User-saved skills: typed instructions the user wants to reuse in any chat (use them with `/skill:`). */
object UserSkills {
    private const val TAG = "dui-user-skills"
    private const val PREFS_NAME = "dui_user_skills"
    private const val KEY_SKILLS = "skills"

    data class UserSkill(
        val id: String,
        val name: String,
        val description: String = "",
        val instruction: String = "",
        val createdAt: String = ""
    )

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun list(context: Context): List<UserSkill> = runCatching {
        val raw = prefs(context).getString(KEY_SKILLS, null) ?: return emptyList()
        val arr = JSONArray(raw)
        val out = ArrayList<UserSkill>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += UserSkill(
                id = o.optString("id").ifBlank { "skill-${out.size + 1}" },
                name = o.optString("name").ifBlank { "Skill ${out.size + 1}" },
                description = o.optString("description"),
                instruction = o.optString("instruction"),
                createdAt = o.optString("createdAt")
            )
        }
        out
    }.getOrDefault(emptyList())

    fun upsert(context: Context, skill: UserSkill): UserSkill {
        val existing = list(context).filter { it.id != skill.id }
        val next = skill.copy(id = skill.id.ifBlank { "skill-${System.currentTimeMillis()}" })
        save(context, existing + next)
        return next
    }

    fun update(context: Context, skill: UserSkill): UserSkill = upsert(context, skill)

    fun delete(context: Context, id: String): Boolean {
        val all = list(context)
        val next = all.filter { it.id != id }
        if (next.size == all.size) return false
        save(context, next)
        return true
    }

    fun deleteAll(context: Context) {
        prefs(context).edit().remove(KEY_SKILLS).apply()
    }

    private fun save(context: Context, skills: List<UserSkill>) {
        val out = JSONArray()
        skills.forEachIndexed { i, s ->
            out.put(org.json.JSONObject()
                .put("id", s.id)
                .put("name", s.name)
                .put("description", s.description)
                .put("instruction", s.instruction)
                .put("createdAt", s.createdAt.ifBlank { "saved-${System.currentTimeMillis()}" }))
            Log.d(TAG, "save user skill: ${s.id}: ${s.name}")
        }
        prefs(context).edit().putString(KEY_SKILLS, out.toString()).apply()
    }
}
