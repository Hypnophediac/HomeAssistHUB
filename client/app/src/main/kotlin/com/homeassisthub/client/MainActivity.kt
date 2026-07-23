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
    val scheme = lightColorScheme(
        primary = Color(0xFF006C4C),
        onPrimary = Color.White,
        primaryContainer = Color(0xFF8CF8C6),
        onPrimaryContainer = Color(0xFF002114),
        secondary = Color(0xFF4C6357),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFCFE9D8),
        onSecondaryContainer = Color(0xFF092013),
        tertiary = Color(0xFF3F6374),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFC3E8FC),
        onTertiaryContainer = Color(0xFF001F2A),
        background = Color(0xFFFBFDF8),
        onBackground = Color(0xFF191C1A),
        surface = Color(0xFFFBFDF8),
        onSurface = Color(0xFF191C1A),
        surfaceVariant = Color(0xFFDCE5DB),
        onSurfaceVariant = Color(0xFF404943),
        error = Color(0xFFBA1A1A),
        onError = Color.White,
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        outline = Color(0xFF707974),
        outlineVariant = Color(0xFFC0C9BF),
        scrim = Color(0xFF000000),
    )
    MaterialTheme(
        colorScheme = scheme,
        content = content
    )
}
