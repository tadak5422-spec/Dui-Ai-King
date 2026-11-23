package ai.dui.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.dui.domain.model.AiModel

private data class ModelCapability(val key: String, val label: String, val icon: ImageVector)

// The order the picker lists them in: text first, then the media inputs.
private val modelCapabilities = listOf(
    ModelCapability("text", "Text", Icons.Outlined.TextFields),
    ModelCapability("image", "Image", Icons.Outlined.Image),
    ModelCapability("audio", "Audio", Icons.Outlined.GraphicEq),
    ModelCapability("video", "Video", Icons.Outlined.Movie),
    ModelCapability("pdf", "PDF", Icons.Outlined.PictureAsPdf)
)

/**
 * The inputs a model accepts, as small badges under its name in the model picker. Only models whose server reports
 * modalities get badges: a "Text only" model is called out, and an unknown one shows nothing rather than a guess.
 */
@Composable
fun ModelCapabilityBadges(model: AiModel, accent: Color, modifier: Modifier = Modifier) {
    if (!model.hasModalityInfo) return
    val shown = if (model.isTextOnly) {
        listOf(ModelCapability("text", "Text only", Icons.Outlined.TextFields))
    } else {
        modelCapabilities.filter { model.accepts(it.key) }
    }
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        shown.forEach { capability ->
            Row(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(accent.copy(alpha = 0.10f))
                    .padding(horizontal = 5.dp, vertical = 1.5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    capability.icon,
                    contentDescription = null,
                    tint = accent.copy(alpha = 0.9f),
                    modifier = Modifier.size(10.dp)
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    capability.label,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = accent.copy(alpha = 0.9f)
                )
            }
        }
    }
}

/**
 * The same information as icons for the chat input's model pill. A text-only model gets a short label instead, so the
 * pill still answers "can this model read images or audio?" at a glance.
 */
@Composable
fun ModelCapabilityIcons(model: AiModel, tint: Color, modifier: Modifier = Modifier) {
    if (!model.hasModalityInfo) return
    val media = modelCapabilities.filter { it.key != "text" && model.accepts(it.key) }
    if (media.isEmpty()) {
        Text(
            "Text only",
            fontSize = 8.5.sp,
            fontWeight = FontWeight.Medium,
            color = tint,
            modifier = modifier
        )
    } else {
        Row(
            modifier,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            media.forEach { capability ->
                Icon(
                    capability.icon,
                    contentDescription = "Reads ${capability.label.lowercase()}",
                    tint = tint,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}
