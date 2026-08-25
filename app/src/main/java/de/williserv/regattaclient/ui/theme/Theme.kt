package de.williserv.regattaclient.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = RegattaBlue,
    secondary = RegattaGreen,
    tertiary = RegattaOrange,
    error = RegattaRed,
    background = RegattaLightBackground,
    surface = RegattaLightSurface,
    surfaceVariant = RegattaLightSurfaceVariant,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onError = Color.White,
    outlineVariant = RegattaDisabledLight,
    inverseOnSurface = Color.White,
    onBackground = Color(0xFF1F2430),
    onSurface = Color(0xFF1F2430),
    onSurfaceVariant = Color(0xFF333844),
    tertiaryContainer = Color(0xFFFFF3CD),
    onTertiaryContainer = Color(0xFF5C4500)
)

private val DarkColorScheme = darkColorScheme(
    primary = RegattaBlueDark,
    secondary = RegattaGreenDark,
    tertiary = RegattaOrangeDark,
    error = RegattaRedDark,
    background = RegattaDarkBackground,
    surface = RegattaDarkSurface,
    surfaceVariant = RegattaDarkSurfaceVariant,
    outlineVariant = RegattaDisabledDark,
    inverseOnSurface = Color.White,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onError = Color.White,
    onBackground = Color(0xFFE8EAF0),
    onSurface = Color(0xFFE8EAF0),
    tertiaryContainer = Color(0xFF252B34),
    onTertiaryContainer = Color(0xFFFFC766),
    onSurfaceVariant = Color(0xFFC9CED8)
)

@Composable
fun RegattaClientTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}