package io.github.srqingchen.chenlu.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = DewPrimaryLight,
    onPrimary = DewOnPrimaryLight,
    primaryContainer = DewPrimaryContainerLight,
    onPrimaryContainer = DewOnPrimaryContainerLight,
    secondary = DewSecondaryLight,
    secondaryContainer = DewSecondaryContainerLight,
    background = DewBackgroundLight,
    surface = DewSurfaceLight,
)

private val DarkColors = darkColorScheme(
    primary = DewPrimaryDark,
    onPrimary = DewOnPrimaryDark,
    primaryContainer = DewPrimaryContainerDark,
    onPrimaryContainer = DewOnPrimaryContainerDark,
    secondary = DewSecondaryDark,
    secondaryContainer = DewSecondaryContainerDark,
    background = DewBackgroundDark,
    surface = DewSurfaceDark,
)

@Composable
fun ChenLuTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = ChenLuTypography,
        content = content,
    )
}
