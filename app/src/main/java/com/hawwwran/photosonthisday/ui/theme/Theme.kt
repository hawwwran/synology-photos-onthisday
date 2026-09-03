package com.hawwwran.photosonthisday.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val Dark = darkColorScheme(
    primary = Palette.Amber,
    secondary = Palette.Orange,
    tertiary = Palette.Rose,
    background = Palette.Night,
    surface = Palette.Night,
    surfaceVariant = Palette.NightRaised,
)

private val Light = lightColorScheme(
    primary = Palette.Orange,
    secondary = Palette.Amber,
    tertiary = Palette.Rose,
)

@Composable
fun OnThisDayTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = if (darkTheme) Dark else Light, content = content)
}
