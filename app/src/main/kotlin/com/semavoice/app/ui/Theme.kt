package com.semavoice.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.semavoice.core.settings.ThemeMode

object Brand {
    val Primary = Color(0xFF4F46E5)
    val Secondary = Color(0xFF14B8A6)
    val LightBg = Color(0xFFF8FAFC)
    val DarkBg = Color(0xFF0B1220)
    val Text = Color(0xFF111827)
    val Border = Color(0xFFE5E7EB)
    val Record = Color(0xFFEF4444)
}

private val LightScheme: ColorScheme = lightColorScheme(
    primary = Brand.Primary, onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E7FF), onPrimaryContainer = Color(0xFF1E1B4B),
    secondary = Brand.Secondary, onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCFBF1), onSecondaryContainer = Color(0xFF134E4A),
    background = Brand.LightBg, onBackground = Brand.Text,
    surface = Color.White, onSurface = Brand.Text,
    surfaceVariant = Color(0xFFF1F5F9), onSurfaceVariant = Color(0xFF475569),
    outline = Brand.Border, error = Brand.Record,
)

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF818CF8), onPrimary = Color(0xFF1E1B4B),
    primaryContainer = Color(0xFF3730A3), onPrimaryContainer = Color(0xFFE0E7FF),
    secondary = Color(0xFF2DD4BF), onSecondary = Color(0xFF042F2E),
    secondaryContainer = Color(0xFF115E59), onSecondaryContainer = Color(0xFFCCFBF1),
    background = Brand.DarkBg, onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF111A2E), onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF1E293B), onSurfaceVariant = Color(0xFFCBD5E1),
    outline = Color(0xFF334155), error = Color(0xFFF87171),
)

/** System font stack: Android's Noto Sans Ethiopic ships on every device and shapes Ge'ez correctly. */
private val SemaTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 42.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 34.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 17.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 16.sp),
)

private val SemaShapes = Shapes(
    small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp),
)

@Composable
fun SemaTheme(mode: ThemeMode, highContrast: Boolean = false, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.DARK -> true; ThemeMode.LIGHT -> false; ThemeMode.SYSTEM -> isSystemInDarkTheme() }
    var scheme = if (dark) DarkScheme else LightScheme
    if (highContrast) scheme = scheme.copy(onSurfaceVariant = scheme.onSurface, outline = scheme.onSurface.copy(alpha = 0.6f))
    MaterialTheme(colorScheme = scheme, typography = SemaTypography, shapes = SemaShapes, content = content)
}
