package com.hawwwran.photosonthisday.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// The launcher icon's palette, so the app and its icon read as one thing.
private val Amber = Color(0xFFFFC24A)
private val Orange = Color(0xFFF4703F)
private val Rose = Color(0xFFE0537A)
private val Night = Color(0xFF1A1628)
private val NightRaised = Color(0xFF241F36)

private val Dark = darkColorScheme(
    primary = Amber,
    secondary = Orange,
    tertiary = Rose,
    background = Night,
    surface = Night,
    surfaceVariant = NightRaised,
)

private val Light = lightColorScheme(
    primary = Orange,
    secondary = Amber,
    tertiary = Rose,
)

@Composable
fun OnThisDayTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = if (darkTheme) Dark else Light, content = content)
}
