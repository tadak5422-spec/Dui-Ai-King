package ai.antigem.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.antigem.theme.QuotaGreen

internal val Amber: Color get() = ai.antigem.theme.AppColors.warning

@Composable
internal fun StepCard(
    number: Int,
    title: String,
    badge: String?,
    cardBg: Color,
    cardBorder: BorderStroke,
    badgeColor: Color = QuotaGreen,
    content: @Composable ColumnScope.() -> Unit
) {
    val done = badge != null && badgeColor == QuotaGreen
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = cardBg), border = cardBorder) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(26.dp).clip(CircleShape)
                        .background(if (done) QuotaGreen else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (done) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
                    else Text("$number", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(12.dp))
                Text(title, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                badge?.let { Pill(it, badgeColor) }
            }
            content()
        }
    }
}

@Composable
internal fun CheckRow(label: String, on: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (on) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null,
            tint = if (on) QuotaGreen else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f), modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(if (on) "On" else "Off", fontSize = 12.sp, color = if (on) QuotaGreen else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
    }
}

@Composable
internal fun Pill(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(6.dp), color = color.copy(alpha = 0.12f)) {
        Text(text, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = color, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp))
    }
}

@Composable
internal fun Hint(text: String) =
    Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))

@Composable
internal fun Mono(text: String) = Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)) {
    Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(10.dp))
}

@Composable
internal fun LogBox(lines: List<String>) {
    val scroll = rememberScrollState()
    LaunchedEffect(lines.size) { scroll.animateScrollTo(scroll.maxValue) }
    Box(
        Modifier.fillMaxWidth().heightIn(max = 180.dp).clip(RoundedCornerShape(8.dp))
            .background(ai.antigem.theme.AppColors.terminalBg).verticalScroll(scroll).padding(10.dp)
    ) {
        Text(lines.joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, color = ai.antigem.theme.AppColors.editorText, lineHeight = 14.sp)
    }
}
