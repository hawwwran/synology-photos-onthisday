package com.hawwwran.photosonthisday.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The launcher icon's palette, so the app and its icon read as one thing. `res/values/colors.xml`
 * carries the same values for the icon's vector drawables, which cannot read Compose colours;
 * the two must be kept equal by hand.
 */
internal object Palette {
    val Today = Color(0xFFFFFFFF)
    val Amber = Color(0xFFFFC24A)
    val Orange = Color(0xFFF4703F)
    val Turquoise = Color(0xFF52D1C4)
    val Rose = Color(0xFFC23B63)
    val Night = Color(0xFF1A1628)
    val NightRaised = Color(0xFF241F36)
}
