package nl.moto.ota.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFF006B5D), onPrimary = Color.White,
    primaryContainer = Color(0xFFABF2DC), onPrimaryContainer = Color(0xFF002019),
    secondary = Color(0xFF48665D), secondaryContainer = Color(0xFFCBEADD),
    background = Color(0xFFF5F8F6), surface = Color(0xFFFCFDFB),
    surfaceVariant = Color(0xFFE4EDE7), onSurface = Color(0xFF18201D),
    onSurfaceVariant = Color(0xFF41534B), outline = Color(0xFF74867C),
    error = Color(0xFFBA1A1A), errorContainer = Color(0xFFFFDAD6)
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF80D6BD), onPrimary = Color(0xFF00382C),
    primaryContainer = Color(0xFF005142), onPrimaryContainer = Color(0xFFABF2DC),
    secondary = Color(0xFFAFCFC1), secondaryContainer = Color(0xFF304C40),
    background = Color(0xFF101713), surface = Color(0xFF17201B),
    surfaceVariant = Color(0xFF26362D), onSurface = Color(0xFFDFE8E0),
    onSurfaceVariant = Color(0xFFBCCDC1), outline = Color(0xFF869B8C),
    error = Color(0xFFFFB4AB), errorContainer = Color(0xFF93000A)
)

@Composable
fun MotoTheme(appearance: AppAppearance, content: @Composable () -> Unit) {
    val dark = when (appearance) {
        AppAppearance.SYSTEM -> isSystemInDarkTheme()
        AppAppearance.DARK -> true
        AppAppearance.LIGHT -> false
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        shapes = Shapes(small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(22.dp)),
        content = content
    )
}
