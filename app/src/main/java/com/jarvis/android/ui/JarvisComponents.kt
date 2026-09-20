package com.jarvis.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.jarvis.android.core.ConversationMessage
import com.jarvis.android.core.ConversationRole

/**
 * A titled card that folds away: the settings page has many sections, so most start closed and show
 * only their title and icon. The open/closed state survives rotation.
 */
@Composable
fun SettingsCard(
    title: String,
    icon: ImageVector,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    val arrow by animateFloatAsState(if (expanded) 180f else 0f, label = "arrow")
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).animateContentSize(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.size(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(
                Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Replier" else "Déplier",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(arrow),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp), content = content)
        }
    }
}

/** A conversation line: the user on the right in the accent colour, the assistant on the left, system lines muted. */
@Composable
fun MessageBubble(message: ConversationMessage, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val (label, container, content, alignEnd) = when (message.role) {
        ConversationRole.USER -> BubbleStyle("Vous", scheme.primaryContainer, scheme.onPrimaryContainer, true)
        ConversationRole.ASSISTANT -> BubbleStyle("Jarvis", scheme.surfaceVariant, scheme.onSurface, false)
        ConversationRole.SYSTEM -> BubbleStyle("Système", Color.Transparent, scheme.onSurfaceVariant, false)
    }
    Row(modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = if (alignEnd) 18.dp else 4.dp, bottomEnd = if (alignEnd) 4.dp else 18.dp))
                .background(container)
                .padding(horizontal = 14.dp, vertical = 9.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = content.copy(alpha = 0.7f))
            Text(message.text, style = MaterialTheme.typography.bodyMedium, color = content)
        }
    }
}

private data class BubbleStyle(val label: String, val container: Color, val content: Color, val alignEnd: Boolean)

/** A small pill with a coloured dot, used for the assistant's state. */
@Composable
fun StatePill(text: String, dot: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.size(8.dp))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * The arc reactor: a soft glow, two thin rings, three arc segments that turn while the assistant is
 * active, and a bright core that swells with the voice. [rotation] is in degrees.
 */
@Composable
fun GlowReactor(color: Color, rotation: Float, coreScale: Float, ringAlpha: Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val c = center
        val r = size.minDimension / 2f
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.32f * ringAlpha + 0.08f), Color.Transparent), center = c, radius = r), radius = r)
        drawCircle(color.copy(alpha = 0.16f + 0.14f * ringAlpha), radius = r * 0.80f, style = Stroke(1.5.dp.toPx()))
        drawCircle(color.copy(alpha = 0.28f + 0.2f * ringAlpha), radius = r * 0.64f, style = Stroke(2.5.dp.toPx()))
        val arcBox = Size(r * 1.36f, r * 1.36f)
        val topLeft = Offset(c.x - arcBox.width / 2, c.y - arcBox.height / 2)
        listOf(0f, 120f, 240f).forEach { start ->
            drawArc(
                color = color.copy(alpha = 0.35f + 0.5f * ringAlpha),
                startAngle = rotation + start, sweepAngle = 62f, useCenter = false,
                topLeft = topLeft, size = arcBox, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round),
            )
        }
        val core = r * 0.34f * coreScale
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.95f), color, color.copy(alpha = 0.55f)), center = c, radius = core), radius = core)
    }
}
