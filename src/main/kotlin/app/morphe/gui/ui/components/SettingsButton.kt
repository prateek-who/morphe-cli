package app.morphe.gui.ui.components

import app.morphe.gui.LocalModeState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.morphe.gui.data.repository.ConfigRepository
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import app.morphe.gui.ui.theme.LocalThemeState

/**
 * Reusable settings button that can be placed on any screen.
 * @param allowCacheClear Whether to allow cache clearing (disable on patches screen and beyond)
 */
@Composable
fun SettingsButton(
    modifier: Modifier = Modifier,
    allowCacheClear: Boolean = true
) {
    val themeState = LocalThemeState.current
    val modeState = LocalModeState.current
    val configRepository: ConfigRepository = koinInject()
    val scope = rememberCoroutineScope()

    var showSettingsDialog by remember { mutableStateOf(false) }
    var autoCleanupTempFiles by remember { mutableStateOf(true) }

    // Load config when dialog is shown
    LaunchedEffect(showSettingsDialog) {
        if (showSettingsDialog) {
            val config = configRepository.loadConfig()
            autoCleanupTempFiles = config.autoCleanupTempFiles
        }
    }

    Box(modifier = modifier) {
        IconButton(
            onClick = { showSettingsDialog = true },
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "Settings",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showSettingsDialog) {
        SettingsDialog(
            currentTheme = themeState.current,
            onThemeChange = { themeState.onChange(it) },
            autoCleanupTempFiles = autoCleanupTempFiles,
            onAutoCleanupChange = { enabled ->
                autoCleanupTempFiles = enabled
                scope.launch {
                    configRepository.setAutoCleanupTempFiles(enabled)
                }
            },
            useSimplifiedMode = modeState.isSimplified,
            onSimplifiedModeChange = { enabled ->
                modeState.onChange(enabled)
            },
            onDismiss = { showSettingsDialog = false },
            allowCacheClear = allowCacheClear
        )
    }
}
