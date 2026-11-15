package com.netfusion.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.netfusion.app.domain.model.ThemePreference

private val DarkColors = darkColorScheme(
    primary = CyanAccent,
    onPrimary = NavyDeep,
    secondary = EmeraldBond,
    tertiary = VioletAccent,
    background = NavyDeep,
    surface = NavySurface,
    surfaceVariant = NavyCard,
    onBackground = Color(0xFFF0F4FF),
    onSurface = Color(0xFFF0F4FF),
    error = CoralError
)

private val LightColors = lightColorScheme(
    primary = BluePrimary,
    onPrimary = Color.White,
    secondary = Color(0xFF00897B),
    tertiary = VioletAccent,
    background = LightBg,
    surface = LightSurface,
    surfaceVariant = LightCard,
    onBackground = Color(0xFF0F172A),
    onSurface = Color(0xFF0F172A),
    error = Color(0xFFD32F2F)
)

@Composable
fun NetFusionTheme(
    themePreference: ThemePreference = ThemePreference.DARK,
    content: @Composable () -> Unit
) {
    val useDark = when (themePreference) {
        ThemePreference.DARK -> true
        ThemePreference.LIGHT -> false
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (useDark) DarkColors else LightColors,
        content = content
    )
}
