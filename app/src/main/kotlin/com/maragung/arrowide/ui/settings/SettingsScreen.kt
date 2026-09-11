package com.maragung.arrowide.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maragung.arrowide.data.SettingsStore
import com.maragung.arrowide.ui.theme.ThemeMode
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Settings screen: theme mode, editor/terminal font sizes, terminal scrollback,
 * session limits — all persisted through [SettingsStore].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    store: SettingsStore,
    modifier: Modifier = Modifier
) {
    val settings by store.settings.collectAsStateWithLifecycle(
        initialValue = SettingsStore.AppSettings()
    )
    val scope = rememberCoroutineScope()

    fun update(transform: (SettingsStore.AppSettings) -> SettingsStore.AppSettings) {
        scope.launch { store.update(transform) }
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Settings") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            SectionHeader("Appearance")
            ThemeMode.entries.forEach { mode ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = settings.themeMode == mode,
                            role = Role.RadioButton,
                            onClick = { update { it.copy(themeMode = mode) } }
                        )
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = settings.themeMode == mode,
                        onClick = { update { it.copy(themeMode = mode) } }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(themeModeLabel(mode))
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            SectionHeader("Editor")
            SettingsSliderRow(
                title = "Editor font size",
                value = settings.editorFontSize,
                valueRange = 10f..24f,
                steps = 13,
                valueLabel = "${settings.editorFontSize} sp"
            ) { v -> update { it.copy(editorFontSize = v) } }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            SectionHeader("Terminal")
            SettingsSliderRow(
                title = "Terminal font size",
                value = settings.terminalFontSize,
                valueRange = 8f..20f,
                steps = 11,
                valueLabel = "${settings.terminalFontSize} sp"
            ) { v -> update { it.copy(terminalFontSize = v) } }
            SettingsSliderRow(
                title = "Scrollback",
                value = settings.scrollbackLines,
                valueRange = 1000f..20000f,
                steps = 18,
                valueLabel = "${settings.scrollbackLines} lines"
            ) { v -> update { it.copy(scrollbackLines = v) } }
            SettingsSliderRow(
                title = "Max terminal sessions",
                value = settings.maxTerminalSessions,
                valueRange = 1f..8f,
                steps = 6,
                valueLabel = "${settings.maxTerminalSessions}"
            ) { v -> update { it.copy(maxTerminalSessions = v) } }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            SectionHeader("Terminal behavior")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 16.dp)
                ) {
                    Text("Warn at session limit")
                    Text(
                        text = "Show a warning when opening more terminals than allowed",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.sessionLimitWarn,
                    onCheckedChange = { checked ->
                        update { it.copy(sessionLimitWarn = checked) }
                    }
                )
            }
            Spacer(Modifier.padding(bottom = 16.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
    )
}

/**
 * Slider row that edits a local value while dragging and only persists the
 * rounded result when the drag finishes (via [onCommit]).
 */
@Composable
private fun SettingsSliderRow(
    title: String,
    value: Int,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueLabel: String,
    onCommit: (Int) -> Unit
) {
    var sliderValue by remember(value) { mutableStateOf(value.toFloat()) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            valueRange = valueRange,
            steps = steps,
            onValueChangeFinished = { onCommit(sliderValue.roundToInt()) }
        )
    }
}

private fun themeModeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "Follow system"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}
