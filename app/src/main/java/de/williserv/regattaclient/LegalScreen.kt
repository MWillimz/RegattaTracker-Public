package de.williserv.regattaclient

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun LegalScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val showThirdPartyLicenses = remember { mutableStateOf(false) }
    val raceSetupPrefs = remember(context) {
        context.getSharedPreferences("race_setup", Context.MODE_PRIVATE)
    }
    val raceServer = remember(raceSetupPrefs) {
        raceSetupPrefs.getString("race_server", "")?.trim().orEmpty()
    }
    val raceEvent = remember(raceSetupPrefs) {
        raceSetupPrefs.getString("race_event", "")?.trim().orEmpty()
    }
    val raceSecret = remember(raceSetupPrefs) {
        raceSetupPrefs.getString("race_secret", "")?.trim().orEmpty()
    }
    var serverMetadata by remember(raceServer, raceEvent, raceSecret) {
        mutableStateOf<ServerMetadata?>(null)
    }

    LaunchedEffect(raceServer, raceEvent, raceSecret) {
        serverMetadata = if (
            raceServer.isBlank() ||
            raceEvent.isBlank() ||
            raceSecret.isBlank()
        ) {
            null
        } else {
            withContext(Dispatchers.IO) {
                fetchServerMetadata(
                    server = raceServer,
                    eventName = raceEvent,
                    sharedSecret = raceSecret
                )
            }
        }
    }

    val thirdPartyLicenseText = remember(context) {
        runCatching {
            val apacheLicense = context.assets
                .open("licenses/zxing-core-apache-2.0.txt")
                .bufferedReader()
                .use { it.readText() }

            buildString {
                appendLine("ZXing Core 3.5.4")
                appendLine("Copyright ZXing authors")
                appendLine("Apache License 2.0")
                appendLine()
                append(apacheLicense.trim())
            }
        }.getOrElse {
            "Third-party license text is unavailable."
        }
    }

    Column(
        modifier = modifier
            .padding(20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Settings / About / Legal",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        LegalCard(
            title = "Legal Notice",
            body = """
                Provider:
                Max Willimzik

                Contact:
                webmaster@raceoffice.williserv.de

                Responsible for contents:
                Max Willimzik
            """.trimIndent()
        )

        Spacer(modifier = Modifier.height(14.dp))

        LegalCard(
            title = "Privacy Policy",
            body = """
                This app processes location data, boat data, and technical sensor data to provide regatta tracking.

                Data processed:
                - GPS position
                - Timestamp
                - Boat data
                - Sail number
                - Captain/skipper name
                - Course over ground (COG)
                - Speed over ground (SOG)
                - GPS accuracy
                - Acceleration and gyroscope data
                - Event identifier, server address and server credential

                Purpose of processing:
                - Live regatta tracking
                - Start line check
                - OCS detection
                - Course progress
                - Finish detection
                - CSV export and later analysis

                Storage and transmission:
                Data is first stored locally on the device. During a race session, tracking data may be transmitted to the compatible regatta server configured for that race. The operator of that server is responsible for server-side processing and retention.

                Android backup:
                App-local configuration, credentials and tracking data are excluded from Android backup and device transfer.

                Manual training:
                Manually recorded training data is stored locally and is not automatically transmitted to the server.

                QR scanning:
                Camera frames used for QR scanning are processed locally and are not intentionally stored or uploaded by the app.

                Consent:
                Location data is only processed if the user consents to processing and grants the Android location permission.
            """.trimIndent()
        )

        serverMetadata?.let { metadata ->
            Spacer(modifier = Modifier.height(14.dp))

            ServerMetadataCard(
                metadata = metadata,
                onOpenUrl = { url ->
                    if (isHttpOrHttpsUrl(url)) {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            )
                        }
                    }
                },
                onContactEmail = { email ->
                    runCatching {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_SENDTO,
                                Uri.fromParts("mailto", email.trim(), null)
                            )
                        )
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        LegalCard(
            title = "License Notices",
            body = """
                Regatta Tracker source code:
                GNU General Public License v3.0 or later.

                QR decoding:
                ZXing Core 3.5.4 — Apache License 2.0.

                The full ZXing license text is bundled with the app and can be viewed below.

                Map and geodata:
                © OpenStreetMap contributors
                © OpenSeaMap contributors

                OpenStreetMap data is available under the Open Data Commons Open Database License (ODbL).
            """.trimIndent()
        )

        TextButton(
            onClick = { showThirdPartyLicenses.value = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Third-party licenses")
        }

        Spacer(modifier = Modifier.height(14.dp))

        LegalCard(
            title = "App",
            body = "Build: ${BuildConfig.APP_VERSION_NAME}"
        )

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Back")
        }
    }

    if (showThirdPartyLicenses.value) {
        AlertDialog(
            onDismissRequest = { showThirdPartyLicenses.value = false },
            title = {
                Text("Third-party licenses")
            },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = thirdPartyLicenseText,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showThirdPartyLicenses.value = false }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
private fun ServerMetadataCard(
    metadata: ServerMetadata,
    onOpenUrl: (String) -> Unit,
    onContactEmail: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp)
        ) {
            Text(
                text = "Connected Regatta Server",
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold
            )

            metadata.operator?.let { operator ->
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Operator",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = operator,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            metadata.publicUrl?.let { publicUrl ->
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Website",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isHttpOrHttpsUrl(publicUrl)) {
                    TextButton(onClick = { onOpenUrl(publicUrl) }) {
                        Text(publicUrl)
                    }
                } else {
                    Text(
                        text = publicUrl,
                        fontSize = 16.sp,
                        lineHeight = 22.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            metadata.contactEmail?.let { contactEmail ->
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Contact",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = { onContactEmail(contactEmail) }) {
                    Text(contactEmail)
                }
            }
        }
    }
}

@Composable
fun LegalCard(
    title: String,
    body: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp)
        ) {
            Text(
                text = title,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = body,
                fontSize = 16.sp,
                lineHeight = 22.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
