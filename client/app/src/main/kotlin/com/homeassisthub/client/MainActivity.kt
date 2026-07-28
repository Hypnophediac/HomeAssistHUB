package com.homeassisthub.client

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.homeassisthub.client.ui.navigation.AppNavHost

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HomeAssistTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppNavHost()
                }
            }
        }
    }
}

@Composable
private fun HomeAssistTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = Color(0xFF10B981),
        onPrimary = Color(0xFF00210F),
        primaryContainer = Color(0xFF0E5C40),
        onPrimaryContainer = Color(0xFFA7F3D0),
        secondary = Color(0xFF06B6D4),
        onSecondary = Color(0xFF00363D),
        secondaryContainer = Color(0xFF0E5560),
        onSecondaryContainer = Color(0xFFB2EBF2),
        tertiary = Color(0xFF8B5CF6),
        onTertiary = Color(0xFF2A1854),
        tertiaryContainer = Color(0xFF4C3080),
        onTertiaryContainer = Color(0xFFE4D6FF),
        background = Color(0xFF121212),
        onBackground = Color(0xFFF8FAFC),
        surface = Color(0xFF1E293B),
        onSurface = Color(0xFFF8FAFC),
        surfaceVariant = Color(0xFF1E293B),
        onSurfaceVariant = Color(0xFF94A3B8),
        surfaceContainerHighest = Color(0xFF334155),
        surfaceContainerHigh = Color(0xFF2A374A),
        surfaceContainer = Color(0xFF1E293B),
        surfaceContainerLow = Color(0xFF19212F),
        surfaceContainerLowest = Color(0xFF0F172A),
        error = Color(0xFFEF4444),
        onError = Color(0xFF3A0000),
        errorContainer = Color(0xFF7A1414),
        onErrorContainer = Color(0xFFFFDAD6),
        outline = Color(0xFF64748B),
        outlineVariant = Color(0xFF334155),
        scrim = Color(0xFF000000),
    )
    MaterialTheme(
        colorScheme = scheme,
        content = content
    )
}
