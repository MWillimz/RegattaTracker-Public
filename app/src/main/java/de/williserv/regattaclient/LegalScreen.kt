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
import androidx.compose.runtime.DisposableEffect
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
    val clientUpdateRequired = remember(context, BuildConfig.VERSION_CODE) {
        mutableStateOf(
            ClientCompatibilityBlockStore.hasAnyBlockForVersion(
                context = context,
                versionCode = BuildConfig.VERSION_CODE
            )
        )
    }

    DisposableEffect(context, BuildConfig.VERSION_CODE) {
        val refreshClientUpdateRequired = {
            clientUpdateRequired.value = ClientCompatibilityBlockStore.hasAnyBlockForVersion(
                context = context,
                versionCode = BuildConfig.VERSION_CODE
            )
        }
        val stopObserving = ClientCompatibilityBlockStore.observeBlockChanges(
            context = context,
            onChanged = refreshClientUpdateRequired
        )
        refreshClientUpdateRequired()

        onDispose {
            stopObserving()
        }
    }

    val thirdPartyLicenseUnavailable = stringResource(R.string.third_party_license_unavailable)
    val zxingLicenseName = stringResource(R.string.zxing_license_name)
    val zxingCopyright = stringResource(R.string.zxing_copyright)
    val apacheLicenseName = stringResource(R.string.apache_license_name)
    val buildText = stringResource(R.string.build_value, BuildConfig.APP_VERSION_NAME)
    val clientUpdateRequiredText = stringResource(R.string.client_update_required)
    val appInfoText = if (clientUpdateRequired.value) {
        "$buildText\n\n$clientUpdateRequiredText"
    } else {
        buildText
    }

    val thirdPartyLicenseText = remember(
        context,
        thirdPartyLicenseUnavailable,
        zxingLicenseName,
        zxingCopyright,
        apacheLicenseName
    ) {
        runCatching {
            val apacheLicense = context.assets
                .open("licenses/zxing-core-apache-2.0.txt")
                .bufferedReader()
                .use { it.readText() }

            buildString {
                appendLine(zxingLicenseName)
                appendLine(zxingCopyright)
                appendLine(apacheLicenseName)
                appendLine()
                append(apacheLicense.trim())
            }
        }.getOrElse {
            thirdPartyLicenseUnavailable
        }
    }

    Column(
        modifier = modifier
            .padding(20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = stringResource(R.string.settings_about_legal),
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        LegalCard(
            title = stringResource(R.string.legal_notice),
            body = stringResource(R.string.legal_notice_body)
        )

        Spacer(modifier = Modifier.height(14.dp))

        LegalCard(
            title = stringResource(R.string.privacy_policy),
            body = stringResource(R.string.privacy_policy_body)
        )

        Spacer(modifier = Modifier.height(14.dp))

        LegalCard(
            title = stringResource(R.string.regatta_server),
            body = stringResource(R.string.regatta_server_body)
        )

        Spacer(modifier = Modifier.height(14.dp))

        LegalCard(
            title = stringResource(R.string.license_notices),
            body = stringResource(R.string.license_notices_body)
        )

        TextButton(
            onClick = { showThirdPartyLicenses.value = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.third_party_licenses))
        }

        Spacer(modifier = Modifier.height(14.dp))

        LegalCard(
            title = stringResource(R.string.app),
            body = appInfoText
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
