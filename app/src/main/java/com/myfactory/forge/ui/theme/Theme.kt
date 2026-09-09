package com.myfactory.forge.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// A deep green primary, chosen to stay legible on the cheap LCD panels this
// app is meant to run on, where a low-saturation blue washes out in sunlight.
private val Forge = Color(0xFF1B4D3E)
private val ForgeLight = Color(0xFF4C8C77)
private val Ember = Color(0xFFB4562A)

private val LightScheme = lightColorScheme(
    primary = Forge,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB8E7D4),
    onPrimaryContainer = Color(0xFF002014),
    secondary = Color(0xFF4B635A),
    tertiary = Ember,
    error = Color(0xFFB3261E),
    surface = Color(0xFFFBFDF9),
    surfaceVariant = Color(0xFFDCE5DF),
    onSurfaceVariant = Color(0xFF404944),
    outline = Color(0xFF707974),
)

private val DarkScheme = darkColorScheme(
    primary = ForgeLight,
    onPrimary = Color(0xFF003827),
    primaryContainer = Color(0xFF00513A),
    onPrimaryContainer = Color(0xFFB8E7D4),
    secondary = Color(0xFFB2CCC0),
    tertiary = Color(0xFFFFB690),
    error = Color(0xFFF2B8B5),
    surface = Color(0xFF101418),
    surfaceVariant = Color(0xFF404944),
    onSurfaceVariant = Color(0xFFBFC9C3),
    outline = Color(0xFF8A938E),
)

/** Semantic colours for diffs and terminal output, resolved per theme. */
data class CodeColors(
    val added: Color,
    val addedBackground: Color,
    val removed: Color,
    val removedBackground: Color,
    val keyword: Color,
    val string: Color,
    val comment: Color,
    val number: Color,
    val annotation: Color,
    val lineNumber: Color,
    val editorBackground: Color,
)

private val LightCodeColors = CodeColors(
    added = Color(0xFF1B5E20),
    addedBackground = Color(0xFFE3F5E7),
    removed = Color(0xFF8E1B18),
    removedBackground = Color(0xFFFCE8E7),
    keyword = Color(0xFF7B1FA2),
    string = Color(0xFF1B5E20),
    comment = Color(0xFF6E7A74),
    number = Color(0xFF0D47A1),
    annotation = Color(0xFFB4562A),
    lineNumber = Color(0xFF9AA5A0),
    editorBackground = Color(0xFFF7FAF8),
)

private val DarkCodeColors = CodeColors(
    added = Color(0xFF7EE2A0),
    addedBackground = Color(0xFF13301F),
    removed = Color(0xFFFF9A94),
    removedBackground = Color(0xFF3A1B1A),
    keyword = Color(0xFFD9A6F0),
    string = Color(0xFF9BE8A8),
    comment = Color(0xFF8A938E),
    number = Color(0xFF9EC7FF),
    annotation = Color(0xFFFFB690),
    lineNumber = Color(0xFF5C6661),
    editorBackground = Color(0xFF0C1013),
)

val LocalCodeColors = androidx.compose.runtime.staticCompositionLocalOf { LightCodeColors }

private val ForgeTypography = Typography(
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
)

/** Monospace, used for code, diffs and terminal output. */
val CodeTextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 13.sp,
    lineHeight = 19.sp,
)

@Composable
fun ForgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    /**
     * Material You, where the platform provides it. Off below API 31, which
     * is most of the devices this app targets, so the hand-picked palette is
     * the one that actually ships to them.
     */
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkScheme
        else -> LightScheme
    }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalCodeColors provides if (darkTheme) DarkCodeColors else LightCodeColors,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = ForgeTypography,
            content = content,
        )
    }
}
