package ai.dui.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.dui.data.remote.UserSkills

private data class SkillDraft(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val instruction: String = ""
)

/** User-typed, app-level skills. Saved to device storage and reusable in any chat via `/skill:`. */
@Composable
fun MySkillsPanel() {
    val context = LocalContext.current
    var skills by remember { mutableStateOf(UserSkills.list(context)) }
    var draft by remember { mutableStateOf(SkillDraft()) }
    var editing by remember { mutableStateOf(false) }

    fun refresh() { skills = UserSkills.list(context) }
    fun saveDraft() {
        val name = draft.name.trim()
        if (name.isBlank()) return
        if (draft.id.isBlank()) {
            UserSkills.upsert(context, UserSkills.UserSkill("", name, draft.description, draft.instruction))
        } else {
            UserSkills.update(context, UserSkills.UserSkill(draft.id, name, draft.description, draft.instruction))
        }
        draft = SkillDraft()
        editing = false
        refresh()
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("My Skills", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "Save custom instructions and reuse them in any chat with /skill:.",
                fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.10f)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Psychology, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(if (editing) "Edit skill" else "New skill", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
                OutlinedTextField(
                    draft.name, { draft = draft.copy(name = it) },
                    label = { Text("Skill name") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    draft.description, { draft = draft.copy(description = it) },
                    label = { Text("Short description (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    draft.instruction, { draft = draft.copy(instruction = it) },
                    label = { Text("Instructions") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 6,
                    placeholder = { Text("e.g. Keep answers short and show Rust function signatures.") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    keyboardActions = KeyboardActions(onDone = { saveDraft() })
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { saveDraft() }, enabled = draft.name.trim().isNotEmpty(), modifier = Modifier.weight(1f)) {
                        Text(if (editing) "Save" else "Add")
                    }
                    if (editing) {
                        TextButton(onClick = { draft = SkillDraft(); editing = false }) { Text("Cancel") }
                    }
                }
            }
        }

        if (skills.isEmpty()) {
            Text(
                "No saved skills yet. Add one with “New skill” above.",
                fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )
        }
        skills.forEach { skill ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f))
            ) {
                Row(modifier = Modifier.padding(14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(skill.name, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (skill.description.isNotBlank()) {
                            Text(skill.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (skill.instruction.isNotBlank()) {
                            Text(
                                skill.instruction,
                                fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                                maxLines = 3, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    IconButton(onClick = {
                        draft = SkillDraft(skill.id, skill.name, skill.description, skill.instruction)
                        editing = true
                    }) { Icon(Icons.Outlined.Edit, "Edit skill") }
                    IconButton(onClick = {
                        UserSkills.delete(context, skill.id)
                        refresh()
                    }) { Icon(Icons.Outlined.Delete, "Delete skill", tint = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}
