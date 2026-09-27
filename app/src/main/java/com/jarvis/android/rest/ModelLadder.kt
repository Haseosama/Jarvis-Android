package com.jarvis.android.rest

/**
 * The text models to try, in order, for every one-shot Gemini call (the chat, web search, looking at the screen or a file, the agent,
 * meeting notes...): the model chosen in the settings first, then others the key can usually reach. A model that fails is set aside for
 * a while, according to why, so nobody waits on it twice (after Mark LV's core/gemini.py, where one model a day meant one daily limit,
 * and an unanswering one cost 12 to 15 seconds on every call):
 *   * out of quota (429, every key tried): 5 minutes, quotas refill;
 *   * not answering (500, 502, 503, 504, a timeout): 30 minutes, an outage outlasts a retry;
 *   * not there, or not for this key (404, a 400 naming the model): 6 hours, it will not appear in a minute.
 * A bad key, a network down or a refused request are not the model's fault: they end the call as before instead of walking the ladder
 * into the same wall. Speech and image models are never replaced by a text one.
 */
internal class ModelLadder(
    private val fallbacks: List<String> = DEFAULT_FALLBACKS,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    /** A ladder of text models (the others are never replaced by one); false for the Live ladder, whose rungs are all Live models. */
    private val textOnly: Boolean = true,
) {
    enum class Failure(val restMs: Long) { QUOTA(5 * 60_000L), UNAVAILABLE(30 * 60_000L), GONE(6 * 60 * 60_000L) }

    private val restingUntil = HashMap<String, Long>()

    private fun key(model: String) = model.removePrefix("models/")

    /** Whether [model] can be stood in for by another text model. */
    fun replaceable(model: String): Boolean {
        val m = key(model).lowercase()
        return listOf("tts", "image", "audio", "live", "embedding", "veo", "imagen").none { it in m }
    }

    @Synchronized
    fun resting(model: String): Boolean = (restingUntil[key(model)] ?: 0L) > now()

    /** The models to try for a call asking for [primary]: it first, then the others, the resting ones left out (all of them resting:
     * the primary anyway, refusing to answer is never better). */
    @Synchronized
    fun candidates(primary: String): List<String> {
        if (textOnly && !replaceable(primary)) return listOf(primary)
        val all = (listOf(key(primary)) + fallbacks.map { key(it) }).distinct()
        val awake = all.filter { (restingUntil[it] ?: 0L) <= now() }
        return awake.ifEmpty { listOf(key(primary)) }
    }

    @Synchronized
    fun rest(model: String, why: Failure) {
        restingUntil[key(model)] = now() + why.restMs
        reasons[key(model)] = why
    }

    private val reasons = HashMap<String, Failure>()

    /** The model that last answered (for the settings), null before the first call. */
    @Volatile var lastAnswered: String? = null
        private set

    fun answered(model: String) { lastAnswered = key(model) }

    /** A model set aside: why, and for how many more minutes. */
    data class Resting(val model: String, val why: Failure, val minutesLeft: Long)

    @Synchronized
    fun resting(): List<Resting> {
        val t = now()
        return restingUntil.filter { it.value > t }.map { (m, until) -> Resting(m, reasons[m] ?: Failure.UNAVAILABLE, (until - t + 59_999) / 60_000) }
            .sortedBy { it.minutesLeft }
    }

    /** Every model back in the running (the user fixed their key or their quota). */
    @Synchronized
    fun reset() { restingUntil.clear(); reasons.clear() }

    companion object {
        /**
         * Quality first (these carry the tools and the French of the chat), then the light ones, which answer fastest; the last two were
         * measured by Mark LV as the least reliable. Names Google publishes; one this key cannot use is set aside for six hours after
         * a single refusal.
         */
        val DEFAULT_FALLBACKS = listOf(
            "gemini-3.5-flash", "gemini-2.5-flash", "gemini-flash-latest",
            "gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-2.5-flash-lite", "gemini-flash-lite-latest",
            "gemini-3.6-flash", "gemini-3-flash-preview",
        )

        /** Why a call failed, as far as the ladder is concerned; null when another model would fail the same way. */
        fun classify(e: RestChatException): Failure? = when (e.httpCode) {
            429 -> Failure.QUOTA
            404 -> Failure.GONE
            500, 502, 503, 504 -> Failure.UNAVAILABLE
            else -> null
        }

        /** One ladder for the whole app, so a model set aside by the chat is also skipped by web search. */
        val shared = ModelLadder()
    }
}
