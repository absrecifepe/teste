package com.asciitype.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Green = Color(0xFF39FF6A)

private val Colors = darkColorScheme(
    primary = Green,
    onPrimary = Color(0xFF002910),
    secondary = Color(0xFF8BD8A0),
    background = Color(0xFF0B0F0C),
    surface = Color(0xFF0B0F0C),
    surfaceVariant = Color(0xFF1A221C),
    onSurfaceVariant = Color(0xFFB5C4B8),
    secondaryContainer = Color(0xFF1F4D2B),
    onSecondaryContainer = Color(0xFFC8F5D2),
)

@Composable
fun AsciiTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
