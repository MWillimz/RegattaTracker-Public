package de.williserv.regattaclient

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ServerInformationEntry(
    server: String,
    event: String,
    secret: String,
    modifier: Modifier = Modifier
) {
    if (server.isBlank() || event.isBlank() || secret.isBlank()) return

    var showServerInformation by remember(server, event, secret) {
        mutableStateOf(false)
    }

    Button(
        onClick = { showServerInformation = true },
        colors = primaryButtonColors(),
        modifier = modifier.fillMaxWidth()
    ) {
        Text("Server information")
    }

    if (showServerInformation) {
        Dialog(
            onDismissRequest = { showServerInformation = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                ServerInformationScreen(
                    server = server,
                    event = event,
                    secret = secret,
                    onClose = { showServerInformation = false }
                )
            }
        }
    }
}

@Composable
private fun ServerInformationScreen(
    server: String,
    event: String,
    secret: String,
    onClose: () -> Unit
) {
    var selectedLegalKind by remember { mutableStateOf<ServerLegalKind?>(null) }

    BackHandler(enabled = selectedLegalKind != null) {
        selectedLegalKind = null
    }

    selectedLegalKind?.let { kind ->
        ServerLegalDocumentView(
            server = server,
            event = event,
            secret = secret,
            kind = kind,
            onBack = { selectedLegalKind = null }
        )
        return
    }

    val context = LocalContext.current
    val eventPayload = remember(server, event, secret) {
        buildEventQrPayload(server, event, secret)
    }
    val eventPageUrl = remember(server, event, secret) {
        buildEventAccessUrl(server, event, secret)
    }
    var serverMetadata by remember(server, event, secret) {
        mutableStateOf<ServerMetadata?>(null)
    }
    var metadataLoaded by remember(server, event, secret) {
        mutableStateOf(false)
    }

    LaunchedEffect(server, event, secret) {
        serverMetadata = withContext(Dispatchers.IO) {
            fetchServerMetadata(
                server = server,
                eventName = event,
                sharedSecret = secret
            )
        }
        metadataLoaded = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Server information",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = event,
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        ConnectedServerCard(
            metadata = serverMetadata,
            metadataLoaded = metadataLoaded,
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
            },
            onShareEvent = {
                shareText(
                    context = context,
                    text = eventPayload,
                    chooserTitle = "Share Event"
                )
            },
            onVisitEventPage = {
                if (isHttpOrHttpsUrl(eventPageUrl)) {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(eventPageUrl))
                        )
                    }
                }
            },
            onShareEventPage = {
                shareText(
                    context = context,
                    text = eventPageUrl,
                    chooserTitle = "Share Event Page"
                )
            }
        )

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = "Server Legal",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { selectedLegalKind = ServerLegalKind.IMPRESSUM },
                        colors = primaryButtonColors(),
                        modifier = Modifier.weight(0.5f)
                    ) {
                        Text("Impressum")
                    }

                    Button(
                        onClick = { selectedLegalKind = ServerLegalKind.DATENSCHUTZ },
                        colors = primaryButtonColors(),
                        modifier = Modifier.weight(0.5f)
                    ) {
                        Text("Datenschutz")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = onClose,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Back")
        }
    }
}

@Composable
private fun ServerLegalDocumentView(
    server: String,
    event: String,
    secret: String,
    kind: ServerLegalKind,
    onBack: () -> Unit
) {
    var document by remember(server, event, secret, kind) {
        mutableStateOf<ServerLegalDocument?>(null)
    }
    var loaded by remember(server, event, secret, kind) {
        mutableStateOf(false)
    }

    LaunchedEffect(server, event, secret, kind) {
        document = withContext(Dispatchers.IO) {
            fetchServerLegalDocument(
                server = server,
                eventName = event,
                sharedSecret = secret,
                kind = kind
            )
        }
        loaded = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = document?.title
                ?.takeIf { it.isNotBlank() }
                ?.let { "Server $it" }
                ?: kind.fallbackTitle,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        when {
            document != null -> {
                Text(
                    text = document!!.content,
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            loaded -> {
                Text(
                    text = "Server legal information is unavailable.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            else -> {
                Text(
                    text = "Loading server legal information…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Back")
        }
    }
}

@Composable
private fun ConnectedServerCard(
    metadata: ServerMetadata?,
    metadataLoaded: Boolean,
    onOpenUrl: (String) -> Unit,
    onContactEmail: (String) -> Unit,
    onShareEvent: () -> Unit,
    onVisitEventPage: () -> Unit,
    onShareEventPage: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = "Connected Regatta Server",
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold
            )

            metadata?.operator?.let { operator ->
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

            metadata?.publicUrl?.let { publicUrl ->
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

            metadata?.contactEmail?.let { contactEmail ->
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

            if (metadataLoaded && metadata == null) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Server operator information is unavailable.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Event access",
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Button(
                onClick = onShareEvent,
                colors = primaryButtonColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Text("Share Event")
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onVisitEventPage,
                    colors = primaryButtonColors(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Visit Event Page")
                }

                Button(
                    onClick = onShareEventPage,
                    colors = primaryButtonColors(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Share Event Page")
                }
            }
        }
    }
}

private fun shareText(
    context: Context,
    text: String,
    chooserTitle: String
) {
    runCatching {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(shareIntent, chooserTitle))
    }
}
