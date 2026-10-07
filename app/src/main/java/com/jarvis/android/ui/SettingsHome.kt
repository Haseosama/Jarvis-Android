package com.jarvis.android.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jarvis.android.i18n.tr
import com.jarvis.android.i18n.trf

internal val LocalSettingsFilter = staticCompositionLocalOf { SettingsFilter() }

internal val SettingsGroup.icon: ImageVector
    get() = when (this) {
        SettingsGroup.JARVIS -> Icons.Filled.Face
        SettingsGroup.VOICE -> Icons.Filled.RecordVoiceOver
        SettingsGroup.AI -> Icons.Filled.Psychology
        SettingsGroup.PHONE -> Icons.Filled.PhoneAndroid
        SettingsGroup.ORGANISE -> Icons.Filled.Event
        SettingsGroup.BRIEFINGS -> Icons.Filled.WbSunny
        SettingsGroup.HEALTH -> Icons.Filled.Favorite
        SettingsGroup.CAR -> Icons.Filled.DirectionsCar
        SettingsGroup.FILES -> Icons.Filled.Folder
        SettingsGroup.SERVICES -> Icons.Filled.Cloud
    }

/** One colour per category, on the menu and on the icon of its cards, so a card shows where it belongs. */
internal val SettingsGroup.color: Color
    get() = when (this) {
        SettingsGroup.JARVIS -> Color(0xFF5C6BC0)
        SettingsGroup.VOICE -> Color(0xFFEC407A)
        SettingsGroup.AI -> Color(0xFF7E57C2)
        SettingsGroup.PHONE -> Color(0xFF1E88E5)
        SettingsGroup.ORGANISE -> Color(0xFF43A047)
        SettingsGroup.BRIEFINGS -> Color(0xFFF57C00)
        SettingsGroup.HEALTH -> Color(0xFFE53935)
        SettingsGroup.CAR -> Color(0xFF607D8B)
        SettingsGroup.FILES -> Color(0xFF8D6E63)
        SettingsGroup.SERVICES -> Color(0xFF00ACC1)
    }

/** A white icon on a coloured disc. */
@Composable
internal fun SettingsIcon(icon: ImageVector, color: Color, size: Int = 40) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size((size * 0.55f).dp))
    }
}

/**
 * The top of the settings page: on the menu, the assistant's banner, the search field and the categories; while
 * searching, the field and how many settings match; inside a category, a header naming it.
 */
@Composable
internal fun SettingsHome(filter: SettingsFilter, assistantName: String, onChange: (SettingsFilter) -> Unit) {
    val group = filter.group
    if (group != null) {
        GroupHeader(group)
        return
    }
    if (filter.query.isBlank()) AssistantBanner(assistantName)
    OutlinedTextField(
        value = filter.query,
        onValueChange = { onChange(filter.copy(query = it.take(40))) },
        placeholder = { Text(tr("Rechercher un réglage")) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (filter.query.isNotEmpty()) IconButton(onClick = { onChange(filter.copy(query = "")) }) { Icon(Icons.Filled.Clear, tr("Effacer")) }
        },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    )
    if (filter.query.isNotBlank()) {
        val found = filter.matches { tr(it) }.size
        Text(
            when (found) {
                0 -> tr("Aucun réglage ne correspond.")
                1 -> tr("1 réglage trouvé")
                else -> trf("{0} réglages trouvés", found)
            },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
        SettingsGroup.entries.forEach { g -> GroupTile(g) { onChange(SettingsFilter(group = g)) } }
    }
}

@Composable
private fun AssistantBanner(assistantName: String) {
    val context = LocalContext.current
    val version = remember {
        try { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() } catch (_: Exception) { "" }
    }
    val spin = rememberInfiniteTransition(label = "banner")
    val rotation by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(12_000, easing = LinearEasing)), label = "rotation")
    val pulse by spin.animateFloat(0.9f, 1.05f, infiniteRepeatable(tween(1_800), RepeatMode.Reverse), label = "pulse")
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
            GlowReactor(MaterialTheme.colorScheme.primary, rotation, pulse, 0.8f, Modifier.size(56.dp))
            Spacer(Modifier.size(14.dp))
            Column {
                Text(assistantName.ifBlank { "JARVIS" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    if (version.isBlank()) tr("Réglages de l’assistant") else trf("Version {0}", version),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun GroupTile(group: SettingsGroup, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            SettingsIcon(group.icon, group.color, size = 42)
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(tr(group.french), style = MaterialTheme.typography.titleMedium)
                Text(
                    tr(group.frenchHint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                "${GROUP_CARDS[group].orEmpty().size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun GroupHeader(group: SettingsGroup) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 4.dp, bottom = 10.dp)) {
        SettingsIcon(group.icon, group.color, size = 48)
        Spacer(Modifier.size(14.dp))
        Text(tr(group.frenchHint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
