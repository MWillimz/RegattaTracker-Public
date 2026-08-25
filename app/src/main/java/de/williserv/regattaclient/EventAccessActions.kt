package de.williserv.regattaclient

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Composable
fun EventAccessActions(
    server: String,
    event: String,
    secret: String,
    modifier: Modifier = Modifier
) {
    if (server.isBlank() || event.isBlank() || secret.isBlank()) {
        return
    }

    val context = LocalContext.current
    var showQrCode by remember { mutableStateOf(false) }
    val shareUrl = remember(server, event, secret) {
        buildEventAccessUrl(server, event, secret)
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        OutlinedButton(
            onClick = {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, shareUrl)
                }
                context.startActivity(Intent.createChooser(shareIntent, "Share Event"))
            },
            modifier = Modifier.weight(1f)
        ) {
            Text("Share Event")
        }

        OutlinedButton(
            onClick = { showQrCode = true },
            modifier = Modifier.weight(1f)
        ) {
            Text("Show QR Code")
        }
    }

    if (showQrCode) {
        EventQrDialog(
            event = event,
            payload = remember(server, event, secret) {
                buildEventQrPayload(server, event, secret)
            },
            onDismiss = { showQrCode = false }
        )
    }
}

@Composable
private fun EventQrDialog(
    event: String,
    payload: String,
    onDismiss: () -> Unit
) {
    val qrBitmap = remember(payload) {
        generateEventQrBitmap(payload)
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "Event QR Code",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = event,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                if (qrBitmap != null) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color.White
                    ) {
                        Box(modifier = Modifier.padding(12.dp)) {
                            Image(
                                bitmap = qrBitmap,
                                contentDescription = "Event access QR code",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f)
                            )
                        }
                    }
                } else {
                    Text(
                        text = "QR code could not be generated.",
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Close")
                }
            }
        }
    }
}

internal fun buildEventAccessUrl(
    server: String,
    event: String,
    secret: String
): String {
    val baseUrl = server.trim().trimEnd('/')
    return "$baseUrl/event-access" +
            "?event_name=${encodeQueryParameter(event)}" +
            "&secret=${encodeQueryParameter(secret)}"
}

internal fun buildEventQrPayload(
    server: String,
    event: String,
    secret: String
): String = buildString {
    append("{\"server\":")
    appendJsonString(server)
    append(",\"event\":")
    appendJsonString(event)
    append(",\"secret\":")
    appendJsonString(secret)
    append('}')
}

private fun encodeQueryParameter(value: String): String {
    return URLEncoder.encode(value, StandardCharsets.UTF_8.name())
        .replace("+", "%20")
        .replace("%7E", "~")
}

private fun StringBuilder.appendJsonString(value: String) {
    append('"')
    value.forEach { char ->
        when (char) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> {
                if (char.code < 0x20) {
                    append("\\u")
                    append(char.code.toString(16).padStart(4, '0'))
                } else {
                    append(char)
                }
            }
        }
    }
    append('"')
}

private fun generateEventQrBitmap(payload: String) = runCatching {
    val size = 768
    val matrix = MultiFormatWriter().encode(
        payload,
        BarcodeFormat.QR_CODE,
        size,
        size
    )
    val pixels = IntArray(size * size)

    for (y in 0 until size) {
        val rowOffset = y * size
        for (x in 0 until size) {
            pixels[rowOffset + x] = if (matrix[x, y]) {
                android.graphics.Color.BLACK
            } else {
                android.graphics.Color.WHITE
            }
        }
    }

    Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, size, 0, 0, size, size)
    }.asImageBitmap()
}.getOrNull()
