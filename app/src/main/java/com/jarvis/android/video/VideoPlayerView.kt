package com.jarvis.android.video

import android.annotation.SuppressLint
import android.media.MediaPlayer
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.jarvis.android.i18n.tr

/** The video shown in place of the avatar: a header (its title, the sound, close) over the picture, 16:9. */
@Composable
internal fun VideoPlayerView(panel: VideoPanel, video: VideoPanel.Video, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                video.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            IconButton(onClick = { panel.setSound(!video.sound) }) {
                Icon(
                    if (video.sound) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                    contentDescription = if (video.sound) tr("Couper le son de la vidéo") else tr("Mettre le son de la vidéo"),
                )
            }
            IconButton(onClick = { panel.close() }) { Icon(Icons.Filled.Close, contentDescription = tr("Fermer la vidéo")) }
        }
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Color.Black)) {
            when {
                video.youtubeId != null -> YoutubePlayer(video.youtubeId, video.sound)
                video.url != null -> FilePlayer(video.url, video.sound) { panel.close() }
            }
        }
    }
}

/**
 * YouTube's own embedded player in a web view (the privacy-enhanced address), started muted; the sound is switched through the
 * player's JavaScript interface. The page is given a web origin so YouTube accepts to be embedded.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun YoutubePlayer(id: String, sound: Boolean) {
    val web = remember(id) { arrayOfNulls<WebView>(1) }
    AndroidView(
        factory = { ctx ->
            // a debug build can be inspected from a computer (chrome://inspect)
            if (ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) WebView.setWebContentsDebuggingEnabled(true)
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                webViewClient = WebViewClient()
                webChromeClient = WebChromeClient()
                setBackgroundColor(android.graphics.Color.BLACK)
                loadDataWithBaseURL("https://jarvis.android.local/", youtubePage(id), "text/html", "utf-8", null)
                web[0] = this
            }
        },
        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
    )
    LaunchedEffect(sound) {
        // the player may still be loading: the command is sent a few times
        repeat(6) {
            web[0]?.evaluateJavascript(if (sound) "setSound(true)" else "setSound(false)", null)
            kotlinx.coroutines.delay(500)
        }
    }
    DisposableEffect(id) { onDispose { web[0]?.apply { loadUrl("about:blank"); destroy() }; web[0] = null } }
}

internal fun youtubePage(id: String): String = """
<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
<style>html,body{margin:0;height:100%;background:#000}iframe{position:absolute;inset:0;width:100%;height:100%;border:0}</style></head>
<body><iframe id="p" referrerpolicy="strict-origin-when-cross-origin" allow="autoplay; encrypted-media; picture-in-picture" allowfullscreen
 src="https://www.youtube-nocookie.com/embed/$id?autoplay=1&mute=1&playsinline=1&rel=0&modestbranding=1&enablejsapi=1&origin=https%3A%2F%2Fjarvis.android.local"></iframe>
<script>
function cmd(f,a){document.getElementById('p').contentWindow.postMessage(JSON.stringify({event:'command',func:f,args:a||[]}),'*');}
function setSound(on){ if(on){cmd('unMute');cmd('setVolume',[100]);} else {cmd('mute');} }
</script></body></html>
""".trimIndent()

/** A video file on the web, in the phone's own player; closed when it ends. */
@Composable
private fun FilePlayer(url: String, sound: Boolean, onEnd: () -> Unit) {
    val player = remember(url) { arrayOfNulls<MediaPlayer>(1) }
    AndroidView(
        factory = { ctx ->
            VideoView(ctx).apply {
                setOnPreparedListener { mp ->
                    player[0] = mp
                    val v = if (sound) 1f else 0f
                    mp.setVolume(v, v)
                    start()
                }
                setOnCompletionListener { onEnd() }
                setOnErrorListener { _, _, _ -> onEnd(); true }
                setVideoURI(Uri.parse(url))
            }
        },
        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
    )
    LaunchedEffect(sound) {
        val v = if (sound) 1f else 0f
        try { player[0]?.setVolume(v, v) } catch (_: IllegalStateException) {}
    }
}
