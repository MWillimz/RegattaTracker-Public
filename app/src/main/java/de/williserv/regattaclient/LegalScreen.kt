package de.williserv.regattaclient

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource

@Composable
fun LegalScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val showThirdPartyLicenses = remember { mutableStateOf(false) }

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
            stringResource(R.string.third_party_license_unavailable)
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

        Spacer(modifier = Modifier.height(14.dp))

        LegalCard(
            title = "Regatta Server",
            body = """
                The Regatta Server configured for an event is operated separately from this app.

                Its operator and legal information are specific to that server and can be found under Server information on the Event screen.
            """.trimIndent()
        )

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
            Text(stringResource(R.string.third_party_licenses))
        }

        Spacer(modifier = Modifier.height(14.dp))

        LegalCard(
            title = "App",
            body = stringResource(R.string.build_value, BuildConfig.APP_VERSION_NAME)
        )

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.back))
        }
    }

    if (showThirdPartyLicenses.value) {
        AlertDialog(
            onDismissRequest = { showThirdPartyLicenses.value = false },
            title = {
                Text(stringResource(R.string.third_party_licenses))
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
                    Text(stringResource(R.string.close))
                }
            }
        )
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
