package com.jarvis.android.google

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.jarvis.android.JarvisApp
import com.jarvis.android.i18n.tr
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The screen-less activity that asks Google for the access to Gmail and Drive (the system shows the consent sheet) and records that the
 * account is connected. It closes itself when done.
 */
class GoogleConnectActivity : ComponentActivity() {
    companion object {
        private const val TAG = "GoogleConnect"

        /** true: also ask for sorting the mail (spam, the bin, filters). */
        const val EXTRA_CLEANUP = "cleanup"
    }

    /** Asked for with the button, or allowed once before: a plain « Reconnecter Google » must not drop it (it did, and Jarvis then said it could not sort the spam). */
    private var cleanup = false

    private val configStore get() = (applicationContext as JarvisApp).container.configStore

    private val consent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        try {
            done(Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(result.data))
        } catch (e: ApiException) {
            Log.e(TAG, "consent result failed: status=${e.statusCode} message=${e.message} status=${e.status}", e)
            fail(GoogleAuth.explain(e, this))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            try {
                cleanup = intent.getBooleanExtra(EXTRA_CLEANUP, false) || configStore.googleCleanup.first()
                val result = GoogleAuth.authorize(this@GoogleConnectActivity, cleanup)
                val pending = result.pendingIntent
                if (result.hasResolution() && pending != null) consent.launch(IntentSenderRequest.Builder(pending.intentSender).build()) else done(result)
            } catch (e: ApiException) {
                Log.e(TAG, "authorize failed: status=${e.statusCode} message=${e.message} status=${e.status}", e)
                fail(GoogleAuth.explain(e, this@GoogleConnectActivity))
            } catch (e: Exception) {
                Log.e(TAG, "authorize failed", e)
                fail(e.message ?: tr("Connexion à Google impossible."))
            }
        }
    }

    private fun done(result: AuthorizationResult) {
        if (result.accessToken == null) {
            fail(tr("Google n’a pas accordé l’accès."))
            return
        }
        lifecycleScope.launch {
            configStore.setGoogleConnected(true)
            if (cleanup) configStore.setGoogleCleanup(true)
            val sortingAsked = intent.getBooleanExtra(EXTRA_CLEANUP, false)
            Toast.makeText(this@GoogleConnectActivity, if (sortingAsked) tr("Tri des mails autorisé.") else tr("Google connecté."), Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun fail(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }
}
