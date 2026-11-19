package ai.dui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import ai.dui.data.preferences.AuthPreferences
import ai.dui.theme.DuiTheme
import ai.dui.theme.isSystemInDarkThemeRobust
import ai.dui.ui.components.LocalTerminalDialog

class TerminalActivity : ComponentActivity() {

    private val authPreferences: AuthPreferences by lazy { AuthPreferences(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ai.dui.data.service.DuiAIService.start(this)

        setContent {
            val initialThemeMode = remember { authPreferences.getThemeModeSync() }
            val themeMode by authPreferences.themeMode.collectAsState(initial = initialThemeMode)
            val isSystemDark = isSystemInDarkThemeRobust()
            val useDarkTheme = when (themeMode) {
                "DARK" -> true
                "LIGHT" -> false
                else -> isSystemDark
            }

            DuiTheme(darkTheme = useDarkTheme) {
                LocalTerminalDialog(
                    onDismiss = { finish() }
                )
            }
        }
    }
}
