package com.jarvis.android.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import com.jarvis.android.JarvisApp
import com.jarvis.android.MainActivity
import com.jarvis.android.ui.loadAttachment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Target of "Share > Jarvis". It only hands the shared text or file over to the chat and opens it: nothing is
 * sent to Gemini until the user taps an action there, and shared content is treated as data, never as instructions.
 */
class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as JarvisApp).container
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        CoroutineScope(Dispatchers.Main).launch {
            var fileName: String? = null
            if (stream != null) {
                val failure = loadAttachment(this@ShareReceiverActivity, stream)
                if (failure != null) Toast.makeText(this@ShareReceiverActivity, failure, Toast.LENGTH_LONG).show()
                else fileName = container.attachedFiles.current.value?.name
            }
            container.shareInbox.offer(text, fileName)
            startActivity(
                Intent(this@ShareReceiverActivity, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_OPEN_CHAT)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            )
            finish()
        }
    }
}
