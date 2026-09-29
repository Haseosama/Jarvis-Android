package com.jarvis.android.space

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.android.JarvisApp
import com.jarvis.android.video.VideoPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Request

private val L_BG = Color(0xFF050B14)
private val L_TEXT = Color(0xFFDCEBFA)
private val L_DIM = Color(0xFF8FA9C4)
private val L_GO = Color(0xFF4DFFB8)
private val L_WAIT = Color(0xFFFFD54F)
private val L_ACCENT = Color(0xFFFF9F43)

private fun statusColor(s: String) = when (s) { "Go", "In Flight", "Success" -> L_GO; "Hold", "Failure", "Partial Failure" -> Color(0xFFFF4D8D); else -> L_WAIT }

/** The next rocket launches where the face is: the first with its picture and a countdown to the second, the others below; touch one for its webcast. */
@Composable
internal fun LaunchesView(big: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as JarvisApp).container }
    val launches by produceState<List<Launch>?>(null) { value = Launches.upcoming(container) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1_000) } }
    val first = launches?.firstOrNull()
    val picture by produceState<ImageBitmap?>(null, first?.image) {
        val url = first?.image?.takeIf { it.startsWith("https://") } ?: return@produceState
        value = withContext(Dispatchers.IO) {
            try {
                container.http.newCall(Request.Builder().url(url).header("User-Agent", "Jarvis-Android").build()).execute().use { r ->
                    r.body?.bytes()?.takeIf { r.isSuccessful }?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
                }
            } catch (_: Exception) {
                null
            }
        }
    }
    fun watch(l: Launch) { l.youtube?.let { container.videoPanel.show(VideoPanel.Video(youtubeId = it, title = l.name, sound = true)) } }

    Column(modifier.fillMaxSize().background(L_BG).verticalScroll(rememberScrollState()).padding(10.dp)) {
        val list = launches
        when {
            list == null -> Text("Chargement des lancements…", color = L_DIM)
            list.isEmpty() -> Text("Liste des lancements indisponible pour l’instant (le service limite les demandes) : réessayez dans quelques minutes.", color = L_DIM)
            else -> {
                val l = list.first()
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = l.youtube != null) { watch(l) }) {
                    picture?.let { Image(it, null, Modifier.fillMaxWidth().height(if (big) 260.dp else 150.dp), contentScale = ContentScale.Crop) }
                    Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0xB0050B14)).padding(8.dp)) {
                        Text(tMinus(l.netMs - now), color = L_ACCENT, fontSize = if (big) 34.sp else 26.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        Text(l.name, color = L_TEXT, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("${l.rocket} · ${l.provider}", color = L_TEXT, style = MaterialTheme.typography.labelLarge)
                Text("${l.pad}, ${l.place}", color = L_DIM, style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(launchWhen(l).replaceFirstChar { it.uppercase() }, color = L_DIM, style = MaterialTheme.typography.labelMedium)
                    Text(launchStatusWords(l.status), color = statusColor(l.status), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
                l.probability?.let { Text("Météo favorable à $it %", color = L_DIM, style = MaterialTheme.typography.labelSmall) }
                if (l.orbit.isNotBlank()) Text("Orbite : ${l.orbit}", color = L_DIM, style = MaterialTheme.typography.labelSmall)
                if (l.youtube != null) Text(if (l.live) "● En direct : touchez l’image" else "Direct annoncé : touchez l’image", color = if (l.live) Color(0xFFFF4D8D) else L_GO, style = MaterialTheme.typography.labelSmall)
                if (big && l.description.isNotBlank()) Text(l.description, color = L_TEXT, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(10.dp))
                Text("Ensuite", color = L_DIM, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                list.drop(1).take(if (big) 12 else 6).forEach { n ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = n.youtube != null) { watch(n) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(statusColor(n.status)))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(n.name, color = L_TEXT, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                            Text("${n.place} · ${launchWhen(n)}", color = L_DIM, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                        Text(countdownWords(n.netMs - now).removePrefix("dans "), color = L_ACCENT, style = MaterialTheme.typography.labelMedium)
                    }
                }
                Text("Données : The Space Devs (Launch Library 2)", color = L_DIM, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}
