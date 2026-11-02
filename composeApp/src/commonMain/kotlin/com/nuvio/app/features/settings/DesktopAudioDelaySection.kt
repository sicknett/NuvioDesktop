package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Audio/video offset for Bluetooth headphones, stored by [DesktopAudioSettings] and applied when a video starts. */
@Composable
internal fun DesktopAudioDelaySection(isTablet: Boolean) {
    var delayMs by remember { mutableIntStateOf(DesktopAudioSettings.delayMs) }
    fun change(newValue: Int) {
        val clamped = newValue.coerceIn(DESKTOP_AUDIO_DELAY_MIN_MS, DESKTOP_AUDIO_DELAY_MAX_MS)
        delayMs = clamped
        DesktopAudioSettings.setDelayMs(clamped)
    }

    SettingsSection(title = "Audio sync", isTablet = isTablet) {
        SettingsGroup(isTablet = isTablet) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = if (isTablet) 16.dp else 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Audio offset",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "If sound from Bluetooth headphones runs behind the picture, raise this to delay the " +
                            "video to match. Applies the next time a video starts.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { change(delayMs - DESKTOP_AUDIO_DELAY_STEP_MS) }) { Text("−") }
                    Text(
                        text = if (delayMs > 0) "+$delayMs ms" else "$delayMs ms",
                        modifier = Modifier.widthIn(min = 72.dp),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    OutlinedButton(onClick = { change(delayMs + DESKTOP_AUDIO_DELAY_STEP_MS) }) { Text("+") }
                }
            }
        }
    }
}
