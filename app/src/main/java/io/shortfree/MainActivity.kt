// 앱 설정을 관찰하고 접근성 안내, 기기 설정과 앱별 차단 설정을 연결한다.
package io.shortfree

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.shortfree.accessibility.ShortFreeService
import io.shortfree.data.SettingsRepository
import io.shortfree.data.ShortFreeSettings
import io.shortfree.engine.TargetApp
import java.io.IOException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = SettingsRepository(applicationContext)
        setContent {
            val settings by repository.settings.collectAsStateWithLifecycle(initialValue = null)
            val connected by ShortFreeService.connected.collectAsStateWithLifecycle()
            var savingConsent by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            val base = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            val scheme = base.copy(
                primary = colorResource(R.color.action),
                onPrimary = colorResource(R.color.on_action),
                surface = colorResource(R.color.canvas),
                onSurface = colorResource(R.color.ink),
                surfaceContainer = colorResource(R.color.panel),
            )
            MaterialTheme(colorScheme = scheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val current = settings
                    if (current != null) {
                        SettingsScreen(
                            settings = current,
                            connected = connected,
                            savingConsent = savingConsent,
                            onSettings = ::openSettings,
                            onAccept = {
                                if (!savingConsent) {
                                    savingConsent = true
                                    scope.launch {
                                        try {
                                            repository.acceptDisclosure()
                                            openSettings()
                                        } catch (_: IOException) {
                                            showWriteError()
                                        } finally {
                                            savingConsent = false
                                        }
                                    }
                                }
                            },
                            onDecline = ::finish,
                            onToggle = { app, enabled ->
                                scope.launch {
                                    try {
                                        repository.setEnabled(app, enabled)
                                    } catch (_: IOException) {
                                        showWriteError()
                                    }
                                }
                            },
                            onReelSwipeGuardChanged = { enabled ->
                                scope.launch {
                                    try {
                                        repository.setReelSwipeGuardEnabled(enabled)
                                    } catch (_: IOException) {
                                        showWriteError()
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    private fun openSettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun showWriteError() {
        Toast.makeText(this, R.string.settings_write_error, Toast.LENGTH_LONG).show()
    }
}

@Composable
internal fun SettingsScreen(
    settings: ShortFreeSettings,
    connected: Boolean,
    savingConsent: Boolean,
    onSettings: () -> Unit,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onToggle: (TargetApp, Boolean) -> Unit,
    onReelSwipeGuardChanged: (Boolean) -> Unit = {},
) {
    var helpVisible by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth()
                .verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall)
                Text(
                    text = stringResource(R.string.app_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!settings.consentAccepted) {
                Text(stringResource(R.string.disclosure_title), Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.disclosure_body))
                Button(onClick = onAccept, enabled = !savingConsent) { Text(stringResource(R.string.agree)) }
                TextButton(onClick = onDecline, enabled = !savingConsent) { Text(stringResource(R.string.later)) }
            } else {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val status = if (connected) R.string.running else R.string.stopped
                        Text(stringResource(status), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.rule))
                        TextButton(onClick = onSettings) { Text(stringResource(R.string.open_settings)) }
                    }
                }
                Text(stringResource(R.string.app_settings), Modifier.semantics { heading() },
                    style = MaterialTheme.typography.titleMedium)
                AppSwitch(R.string.youtube, settings.youtubeEnabled) { onToggle(TargetApp.YOUTUBE, it) }
                HorizontalDivider()
                AppSwitch(R.string.instagram, settings.instagramEnabled) { onToggle(TargetApp.INSTAGRAM, it) }
                AppSwitch(
                    R.string.reel_swipe_guard,
                    settings.reelSwipeGuardEnabled,
                    enabled = settings.instagramEnabled,
                    onCheckedChange = onReelSwipeGuardChanged,
                )
                Text(stringResource(R.string.reel_swipe_guard_detail), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { helpVisible = true }) { Text(stringResource(R.string.help)) }
                Text(stringResource(R.string.privacy), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (helpVisible) {
        AlertDialog(
            onDismissRequest = { helpVisible = false },
            title = { Text(stringResource(R.string.help)) },
            text = { Text(stringResource(R.string.help_body), Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = {
                TextButton(onClick = { helpVisible = false }) { Text(stringResource(R.string.close)) }
            },
        )
    }
}

@Composable
private fun AppSwitch(
    label: Int,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(label), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, enabled = enabled, onCheckedChange = null)
    }
}
