package com.jarvis.android.avatar

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.content.Context
import android.os.SystemClock
import com.jarvis.android.core.JarvisState

/** Reduces the assistant's state to what the face expresses. */
internal fun moodFor(state: JarvisState): Mood = when (state) {
    JarvisState.ASLEEP, JarvisState.ERROR -> Mood.ASLEEP
    JarvisState.LISTENING -> Mood.LISTENING
    JarvisState.THINKING -> Mood.THINKING
    JarvisState.CONNECTING, JarvisState.SPEAKING -> Mood.IDLE
}

/**
 * The one avatar of the app: the session engine feeds it the voice (as it is about to be played) and the transcript,
 * and the HUD draws it. The head is loaded from the asset the first time it is needed.
 */
internal class AvatarController(private val context: Context) {
    /** Set from the settings: when off, nothing is analysed and nothing is drawn. */
    @Volatile var enabled = true

    /** The light mode (see ConfigStore.avatarLight). */
    @Volatile var light = false

    /** How the head is dressed: 0 = the glowing web, 1..4 = a skin tone, 5 = the hologram over the skin (the default), 6 = the same with hair of optical fibres, 7 = the blue hologram (light blue with deep blue accents); lip colour 0 = natural, 1..4 = rose, red, plum, coral. */
    @Volatile var skin = 7   // the blue hologram by default (avatar.BLUE_HOLO_SKIN)
    @Volatile var lips = 0
    /** 0 = no cap, 1..5 = black, blue, red, white, khaki, 6 = grey-green with an emblem. */
    @Volatile var cap = 0

    /** Debug builds can force the face's mood (see DebugAvatarReceiver); null means "follow the assistant's state". */
    @Volatile var debugMood: Mood? = null
    @Volatile var debugYaw: Float? = null
    @Volatile var debugPitch: Float? = null
    @Volatile var debugRoll: Float? = null
    @Volatile var debugMouth: Float? = null

    val timeline = VisemeTimeline()
    private val stream = VisemeStream()

    /** Which head is shown (index in [AVATAR_FACES]). A Compose state, so the view redraws with the new head when it changes. */
    var model by androidx.compose.runtime.mutableIntStateOf(0)

    /** The hairstyle chosen for the current face (a HairChoice id), empty for the face's own hair. A Compose state, as [model]. */
    var hair by androidx.compose.runtime.mutableStateOf("")

    /** The hair colour chosen for the current face (a HairShade id), empty for the face's own. A Compose state, as [model]. */
    var hairColour by androidx.compose.runtime.mutableStateOf("")

    /** The hairstyles offered (assets/avatar/hair/styles.json). */
    val hairChoices: List<HairChoice> by lazy {
        try { HairChoice.parseList(context.assets.open("avatar/hair/styles.json").use { String(it.readBytes(), Charsets.UTF_8) }) }
        catch (_: Exception) { emptyList() }
    }

    private val heads = HashMap<Int, HeadMesh>()
    private val loaded = HashMap<Triple<Int, String, String>, Pair<HeadMesh, HoloAvatar>>()

    @Synchronized
    private fun current(): Pair<HeadMesh, HoloAvatar> {
        val m = model.coerceIn(0, AVATAR_FACES.lastIndex)
        val key = Triple(m, hair, hairColour)
        return loaded.getOrPut(key) {
            // one head with its chosen hair or colour kept at a time besides the plain ones: a fitted head is a few megabytes
            loaded.keys.filter { (it.second.isNotEmpty() || it.third.isNotEmpty()) && it != key }.forEach { loaded.remove(it) }
            val base = heads.getOrPut(m) { HeadMesh.parse(context.assets.open(avatarFace(m).asset).use { it.readBytes() }) }
            val face = avatarFace(m)
            val shade = hairShade(key.third)
            val colours = shade?.colours ?: face.hairColours
            val mesh = hairChoices.firstOrNull { it.id == key.second }?.let { choice ->
                try {
                    HairStyle.parse(context.assets.open(choice.asset).use { it.readBytes() }).fitOn(base, colours)
                } catch (_: Exception) {
                    null
                }
            } ?: if (shade != null) recolourHair(base, face.hairColours, shade.colours) else base
            mesh to HoloAvatar(mesh)
        }
    }

    val mesh: HeadMesh get() = current().first
    val avatar: HoloAvatar get() = current().second

    /** The head and its animation together: taken apart, the face or the hair may change between the two and they would not match. */
    fun head(): Pair<HeadMesh, HoloAvatar> = current()

    init {
        CharacterCatalog.refresh(context)
    }

    private val characters = HashMap<String, CharacterMesh?>()

    /** The textured character of the chosen face, or null when it is a head (or its files could not be read). */
    @Synchronized
    fun character(): CharacterMesh? {
        val folder = avatarFace(model).character ?: return null
        return characters.getOrPut(folder) {
            characters.keys.toList().forEach { characters.remove(it) }          // one atlas in memory at a time
            try { CharacterMesh.load(context.assets, folder) } catch (_: Exception) { null }
        }
    }

    /** Called with each chunk of the assistant's voice (16-bit PCM, 24 kHz), just before it goes to the speaker. */
    fun onSpeech(pcm16: ByteArray) {
        if (!enabled) return
        try {
            val frames = pcmVisemes(pcm16ToShorts(pcm16))
            if (frames.isEmpty()) return
            timeline.push(stream.frames(frames, 0.02f), SystemClock.elapsedRealtimeNanos())
        } catch (_: Exception) {
            // Lip-sync is decoration: a failure must never reach the voice.
        }
    }

    /** The words being spoken, so the lips can close on m, b, p. */
    fun onTranscript(text: String) {
        if (enabled) stream.feedText(text)
    }

    /** The user cut the assistant off, or the session ended: nothing already queued is going to be heard. */
    fun interrupt() {
        timeline.clear()
        stream.reset()
    }
}
