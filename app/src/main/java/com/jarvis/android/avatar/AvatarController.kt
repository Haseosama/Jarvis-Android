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

    /** The feeling of what is being said (see Expressions.kt): the face smiles, worries or is surprised with the words. */
    private val feelings = ExpressionTracker()
    val feeling: Feeling get() = feelings.feeling

    /**
     * The phone's own voice is speaking (the offline mode): it gives no sound to analyse, so the mouth follows the words it says
     * (see [onSpokenWord]), or, on a voice that does not tell which word it is at, the loudness the engine makes up.
     */
    @Volatile var phoneVoice = false
        private set
    @Volatile var phoneVoiceWords = false
        private set

    /** Which head is shown (index in [AVATAR_FACES]). A Compose state, so the view redraws with the new head when it changes. */
    var model by androidx.compose.runtime.mutableIntStateOf(0)

    /** The hairstyle chosen for the current face (a HairChoice id), empty for the face's own hair. A Compose state, as [model]. */
    var hair by androidx.compose.runtime.mutableStateOf("")

    /** A video plays: the face watches it (see HoloAvatar.watching). */
    var watching by androidx.compose.runtime.mutableStateOf(false)

    /** Counts the reactions asked for (a video starts or ends): each new value makes the face react once. */
    var reactions by androidx.compose.runtime.mutableIntStateOf(0)

    /** Where a finger touches the screen, in window pixels, or null: the face follows it with its eyes (see HoloAvatar.follow). */
    @Volatile var finger: androidx.compose.ui.geometry.Offset? = null

    /** The hair colour chosen for the current face (a HairShade id), empty for the face's own. A Compose state, as [model]. */
    var hairColour by androidx.compose.runtime.mutableStateOf("")

    /** The hairstyles offered (assets/avatar/hair/styles.json). */
    val hairChoices: List<HairChoice> by lazy {
        try { HairChoice.parseList(context.assets.open("avatar/hair/styles.json").use { String(it.readBytes(), Charsets.UTF_8) }) }
        catch (_: Exception) { emptyList() }
    }

    /** Haseo's creator sliders (see FaceCustomizer), from the settings. A Compose state, as [model]. */
    var custom by androidx.compose.runtime.mutableStateOf<Map<String, Float>>(emptyMap())

    /** How finely the heads are cut into triangles (see PolygonLevel), from the settings. A Compose state, as [model]. */
    var polygonLevel by androidx.compose.runtime.mutableStateOf(PolygonLevel.MEDIUM)

    /** The polygon editor's retouches of each face (face label → offsets), kept in files (see SculptStore). */
    private val sculpts = HashMap<String, Sculpt>()

    /** Counts the changes of the retouches: the view rebuilds the head when it moves. */
    var sculptVersion by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    /**
     * While the creator is open: the face it shows and the sliders and retouches being tried (null: the saved ones). The face on
     * screen shows them at once, at the Standard level, and nothing is saved before the user applies them.
     */
    var previewModel by androidx.compose.runtime.mutableStateOf<Int?>(null)
    var previewCustom by androidx.compose.runtime.mutableStateOf<Map<String, Float>?>(null)
    var previewSculpt by androidx.compose.runtime.mutableStateOf<Sculpt?>(null)

    /** The face drawn: the one the creator shows, else the chosen one. */
    val shownModel: Int get() = previewModel ?: model

    /** Changes whenever the shape of the head drawn changes (sliders, retouches, polygon level), for the view to rebuild it. */
    val shapeKey: String get() = "${previewCustom ?: custom}|${previewSculpt?.let { MeshSculpt.key(it) } ?: sculptVersion}|${if (previewModel != null) "preview" else polygonLevel.id}"

    fun sculptOf(face: AvatarFace): Sculpt = synchronized(sculpts) { sculpts.getOrPut(face.label) { SculptStore.load(context, face.label) } }

    fun saveSculpt(face: AvatarFace, sculpt: Sculpt) {
        val clean = MeshSculpt.normalize(sculpt)
        synchronized(sculpts) { sculpts[face.label] = clean }
        SculptStore.save(context, face.label, clean)
        sculptVersion++
    }

    private data class HeadKey(val model: Int, val hair: String, val colour: String, val custom: String, val sculpt: String, val level: PolygonLevel)

    private val heads = HashMap<Int, HeadMesh>()
    /** The last heads built (a fitted or finely cut head is several megabytes: two are kept). */
    private val loaded = object : LinkedHashMap<HeadKey, Pair<HeadMesh, HoloAvatar>>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<HeadKey, Pair<HeadMesh, HoloAvatar>>?) = size > 2
    }

    /** The face's head before its hair and its polygon level: Haseo's proportions and sliders, then the retouches. */
    @Synchronized
    fun shapedHead(m: Int, customValues: Map<String, Float>, sculpt: Sculpt): HeadMesh {
        val face = avatarFace(m)
        var mesh = heads.getOrPut(m) {
            val raw = HeadMesh.parse(context.assets.open(face.asset).use { it.readBytes() })
            if (face.haseo) HaseoFace.refine(raw) else raw
        }
        if (face.haseo) mesh = FaceCustomizer.apply(mesh, customValues)
        return MeshSculpt.apply(mesh, sculpt)
    }

    @Synchronized
    private fun current(): Pair<HeadMesh, HoloAvatar> {
        val m = shownModel.coerceIn(0, AVATAR_FACES.lastIndex)
        val face = avatarFace(m)
        val customValues = if (face.haseo) FaceCustomizer.normalize(previewCustom ?: custom) else emptyMap()
        val sculpt = if (face.sculptable()) previewSculpt ?: sculptOf(face) else emptyMap()
        val level = if (previewModel != null || !face.sculptable()) PolygonLevel.MEDIUM else polygonLevel
        val key = HeadKey(m, hair, hairColour, FaceCustomizer.encode(customValues), MeshSculpt.key(sculpt), level)
        return loaded.getOrPut(key) {
            val base = shapedHead(m, customValues, sculpt)
            val shade = hairShade(key.colour)
            val colours = shade?.colours ?: face.hairColours
            val fitted = hairChoices.firstOrNull { it.id == key.hair }?.let { choice ->
                try {
                    HairStyle.parse(context.assets.open(choice.asset).use { it.readBytes() }).fitOn(base, colours)
                } catch (_: Exception) {
                    null
                }
            } ?: if (shade != null) recolourHair(base, face.hairColours, shade.colours) else base
            val mesh = PolygonMesh.apply(fitted, level)
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
        val folder = avatarFace(shownModel).character ?: return null
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

    /** The words being spoken, so the lips can close on m, b, p, and the face shows what they mean. */
    fun onTranscript(text: String) {
        if (!enabled) return
        stream.feedText(text)
        feelings.feed(text)
    }

    /** The user speaks: a new turn, the feeling of the last reply is over. */
    fun onUserSpeech() = feelings.reset()

    /** The phone's voice starts saying [text] (offline mode): the face takes on its feeling. */
    fun onPhoneVoiceStart(text: String) {
        timeline.clear()
        feelings.reset()
        phoneVoiceWords = false
        phoneVoice = true
        if (enabled) feelings.feed(text)
    }

    /** The phone's voice is at [word] (it says so as it goes): the mouth plays its shapes now. */
    fun onSpokenWord(word: String) {
        if (!enabled) return
        phoneVoiceWords = true
        val frames = wordVisemes(word)
        if (frames.isEmpty()) return
        val now = SystemClock.elapsedRealtimeNanos()
        timeline.clear()
        timeline.push(frames, now, latencyNs = 0L)
    }

    fun onPhoneVoiceEnd() {
        phoneVoice = false
        phoneVoiceWords = false
    }

    /** The user cut the assistant off, or the session ended: nothing already queued is going to be heard. */
    fun interrupt() {
        timeline.clear()
        stream.reset()
        feelings.reset()
    }
}
