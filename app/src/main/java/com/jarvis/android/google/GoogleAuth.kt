package com.jarvis.android.google

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.jarvis.android.i18n.tr
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Google sign-in for Gmail and Drive, through Google's Authorization API (Play services): the consent screen and the tokens are handled
 * by Android, Jarvis never sees a password. It needs an OAuth client of type "Android" for this app (package name and signing
 * certificate) in a Google Cloud project, and the user added as a test user while the consent screen is in test mode.
 *
 * The scopes are the narrowest that do the job: read mail, create drafts (nothing is ever sent), read Drive, and add files
 * that Jarvis itself created to Drive.
 */
internal object GoogleAuth {
    val SCOPES = listOf(
        "https://www.googleapis.com/auth/gmail.readonly",
        "https://www.googleapis.com/auth/gmail.compose",
        "https://www.googleapis.com/auth/drive.readonly",
        "https://www.googleapis.com/auth/drive.file",
    )

    fun request(): AuthorizationRequest = AuthorizationRequest.builder().setRequestedScopes(SCOPES.map { Scope(it) }).build()

    /** Asks for authorisation. If the user has to be asked, [AuthorizationResult.hasResolution] is true and the UI must launch its pending intent. */
    suspend fun authorize(context: Context): AuthorizationResult = suspendCancellableCoroutine { cont ->
        Identity.getAuthorizationClient(context).authorize(request())
            .addOnSuccessListener { cont.resume(it) }
            .addOnFailureListener { cont.resumeWithException(it) }
    }

    /** An access token for the API calls, or a message that says what is wrong. Never asks the user anything by itself. */
    suspend fun accessToken(context: Context): Result<String> = try {
        val result = authorize(context)
        val token = result.accessToken
        when {
            token != null -> Result.success(token)
            // Google wants the consent again: the grant was withdrawn, or it expired (about a week in test mode)
            result.hasResolution() -> Result.failure(GoogleException(EXPIRED, needsReconnect = true))
            else -> Result.failure(GoogleException(NOT_CONNECTED, needsReconnect = true))
        }
    } catch (e: ApiException) {
        Result.failure(GoogleException(explain(e, context)))
    } catch (e: Exception) {
        Result.failure(GoogleException(e.message ?: NOT_CONNECTED))
    }

    val EXPIRED: String get() = tr("L’accès Google a expiré ou a été retiré (tant que l’écran de consentement est en mode test, Google le limite à environ une semaine) : l’utilisateur doit toucher « Reconnecter Google » dans les réglages de Jarvis.")

    val NOT_CONNECTED: String get() = tr("Google n’est pas connecté : l’utilisateur doit toucher « Connecter Google » dans les réglages de Jarvis.")

    /** What an [ApiException] usually means here, in plain words. */
    fun explain(e: ApiException, context: Context? = null): String {
        val identity = context?.let { " Paquet : ${it.packageName}, SHA-1 : ${AppIdentity.sha1(it) ?: "?"}." }.orEmpty()
        return when (e.statusCode) {
            10 -> "Google refuse cette application (erreur 10) : il faut un client OAuth de type Android pour ce paquet et cette signature, dans Google Cloud (voir le README).$identity"
            8 -> if (e.message.orEmpty().contains("UNREGISTERED_ON_API_CONSOLE")) "Google ne connaît pas cette application (UNREGISTERED_ON_API_CONSOLE) : aucun client OAuth de type Android ne porte exactement ce nom de paquet et cette empreinte SHA-1 dans le projet Google Cloud.$identity" else "Google Play Services a signalé une erreur interne (8). Le plus souvent, aucun client OAuth de type Android ne correspond à cette application, ou les API Gmail et Drive ne sont pas activées, ou ce compte n’est pas déclaré testeur. Voir le README.$identity"
            7 -> "Pas de connexion réseau."
            12501 -> "Connexion à Google annulée."
            else -> "Google a répondu par une erreur (${e.statusCode}).$identity"
        }
    }
}

/** [needsReconnect]: the connection is gone (not a network or quota problem), so the settings should say "not connected". */
internal class GoogleException(message: String, val needsReconnect: Boolean = false) : Exception(message)
