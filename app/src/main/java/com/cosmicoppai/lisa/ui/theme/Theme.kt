package com.cosmicoppai.lisa.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val LisaBackground = Color(0xFF1A202C)
val LisaSurface = Color(0xFF232B3A)
val LisaSurfaceVariant = Color(0xFF2D3748)
val LisaBorder = Color(0xFF3A4659)
val LisaPrimary = Color(0xFF63B3ED)
val LisaSecondary = Color(0xFF9F7AEA)
val LisaTextPrimary = Color(0xFFEDF2F7)
val LisaTextSecondary = Color(0xFFA0AEC0)
val LisaStarGold = Color(0xFFFDCC0D)
val LisaError = Color(0xFFFC8181)
val LisaSuccess = Color(0xFF68D391)

private val LisaDarkColorScheme = darkColorScheme(
    primary = LisaPrimary,
    onPrimary = Color(0xFF0D1B2A),
    secondary = LisaSecondary,
    onSecondary = Color.White,
    background = LisaBackground,
    onBackground = LisaTextPrimary,
    surface = LisaSurface,
    onSurface = LisaTextPrimary,
    surfaceVariant = LisaSurfaceVariant,
    onSurfaceVariant = LisaTextSecondary,
    outline = LisaBorder,
    error = LisaError,
    onError = Color.Black
)

private val LisaTypography = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = LisaTextPrimary),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = LisaTextPrimary),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = LisaTextPrimary),
    bodyLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, color = LisaTextPrimary),
    bodyMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal, color = LisaTextSecondary),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = LisaTextSecondary)
)

@Composable
fun LisaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LisaDarkColorScheme,
        typography = LisaTypography,
        content = content
    )
}
