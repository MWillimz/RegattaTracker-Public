package de.williserv.regattaclient

import android.app.Activity
import android.content.Intent
import android.os.Bundle

internal fun normalizeSharedText(sharedText: CharSequence?): String? {
    return sharedText
        ?.toString()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}

/**
 * Android share target that normalizes EXTRA_TEXT before forwarding it to MainActivity.
 *
 * EXTRA_TEXT is defined as CharSequence, so browser shares may use implementations other
 * than String (for example styled text). MainActivity's existing import path expects a String.
 */
class EventShareReceiverActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent.action == Intent.ACTION_SEND) {
            val sharedText = normalizeSharedText(
                intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
            )

            val forwardIntent = Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_SEND
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, sharedText ?: "")
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(forwardIntent)
        }

        finish()
    }
}
