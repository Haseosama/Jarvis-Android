package com.jarvis.android.tool

/*
 * What a tool that drives the phone may claim (after Mark LVI's core/outcome.py): a result starts with one word saying what was
 * checked, and the system prompt tells the model what each word may be spoken as. "Done" is never assumed from a call that returned:
 * on Android an app can be refused the screen from the background, a key press can land nowhere, an intent can be swallowed.
 *   [done]        it happened, and it was checked (read back, seen on screen);
 *   [unverified]  the request went out and could not be confirmed: never to be spoken as a success;
 *   [failed]      it did not happen, and the rest says why.
 */
enum class Outcome(val tag: String) {
    DONE("[done]"),
    UNVERIFIED("[unverified]"),
    FAILED("[failed]"),
}

fun done(text: String) = "${Outcome.DONE.tag} $text"
fun unverified(text: String) = "${Outcome.UNVERIFIED.tag} $text"
fun failed(text: String) = "${Outcome.FAILED.tag} $text"

/** The outcome a result starts with, null for a result without one. */
fun outcomeOf(result: String): Outcome? = Outcome.entries.firstOrNull { result.startsWith(it.tag) }

/** The result without its outcome word, for when it is said or shown as is (the offline voice, a notification). */
fun withoutOutcome(result: String): String = outcomeOf(result)?.let { result.removePrefix(it.tag).trimStart() } ?: result

/** The rule the system prompt carries, so the model says no more than the tool knows. */
const val OUTCOME_RULE =
    "TOOL OUTCOMES: a tool result may start with [done], [unverified] or [failed]. [done]: it happened and was checked, say it is done. " +
        "[unverified]: the request was sent but could not be confirmed; never say it is done, say you could not check it (for example " +
        "\"je l'ai demandé, mais je ne peux pas vérifier que c'est fait\"). [failed]: it did not happen; say so and why. Never read the " +
        "bracketed word aloud."
