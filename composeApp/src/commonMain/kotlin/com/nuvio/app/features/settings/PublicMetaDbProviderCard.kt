package com.nuvio.app.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.format.formatLocalDateTime
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.pmdb.PublicMetaDbTracker
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.TrackingSettingsRepository
import com.nuvio.app.features.tracking.WatchProgressSource
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watchprogress.WatchProgressSourceCoordinator
import kotlinx.coroutines.launch

/** Connect/disconnect PublicMetaDB with an API key from publicmetadb.com and import its data. */
@Composable
internal fun PublicMetaDbProviderCard(modifier: Modifier = Modifier) {
    val tokens = MaterialTheme.nuvio
    val connected by remember { PublicMetaDbTracker.ensureLoaded(); PublicMetaDbTracker.isAuthenticated }.collectAsStateWithLifecycle()
    val status by PublicMetaDbTracker.status.collectAsStateWithLifecycle()
    val trackingSettings by remember { TrackingSettingsRepository.ensureLoaded(); TrackingSettingsRepository.uiState }.collectAsStateWithLifecycle()
    val watched by WatchedRepository.uiState.collectAsStateWithLifecycle()
    val progressTick by PublicMetaDbTracker.progressChanges.collectAsStateWithLifecycle(initialValue = Unit)
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var draft by rememberSaveable { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }

    val isActiveSource = trackingSettings.watchProgressSource == WatchProgressSource.PMDB
    val resumeCount = remember(progressTick) { PublicMetaDbTracker.resumePointCount }
    val lastSyncedAt = remember(progressTick) { PublicMetaDbTracker.lastSyncedAtEpochMs }

    /** Makes PublicMetaDB the source for Continue Watching and watched history, then pulls from it. */
    suspend fun useAndSync() {
        val profileId = ProfileRepository.activeProfileId
        if (!isActiveSource) {
            WatchProgressSourceCoordinator.selectSource(profileId = profileId, source = WatchProgressSource.PMDB)
        }
        WatchProgressSourceCoordinator.refreshProviderAndActiveSource(
            profileId = profileId,
            providerId = TrackingProviderId.PMDB,
            refreshProvider = { PublicMetaDbTracker.syncNow() },
        )
    }

    Column(
        modifier = modifier
            .clip(tokens.shapes.card)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), tokens.shapes.card)
            .border(tokens.borders.hairline, Color.White.copy(alpha = 0.2f), tokens.shapes.card)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "PublicMetaDB",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (connected) {
            Text(
                text = if (isActiveSource) {
                    "Continue Watching and watched history come from PublicMetaDB. Finished titles and " +
                        "resume points from this app are saved back to it."
                } else {
                    "Connected, but Continue Watching and watched history are still coming from another " +
                        "source. Use PublicMetaDB to import your data into this app."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (isActiveSource) {
                val syncedText = if (lastSyncedAt > 0L) " · last synced ${formatLocalDateTime(lastSyncedAt)}" else ""
                Text(
                    text = "$resumeCount resume points · ${watched.items.size} watched items$syncedText",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    enabled = !working && !status.isBusy,
                    onClick = {
                        scope.launch {
                            working = true
                            try {
                                useAndSync()
                            } finally {
                                working = false
                            }
                        }
                    },
                ) {
                    Text(
                        when {
                            working || status.isBusy -> "Syncing…"
                            isActiveSource -> "Sync now"
                            else -> "Use PublicMetaDB and import"
                        },
                    )
                }
                OutlinedButton(onClick = { PublicMetaDbTracker.disconnect() }) {
                    Text("Disconnect")
                }
            }
        } else {
            Text(
                text = "Paste an API key from publicmetadb.com. Connecting imports your PublicMetaDB watch " +
                    "history and resume points into Continue Watching.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SettingsSecretTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth(),
                label = "API key",
                enabled = !status.isBusy && !working,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    enabled = draft.isNotBlank() && !status.isBusy && !working,
                    onClick = {
                        scope.launch {
                            working = true
                            try {
                                if (PublicMetaDbTracker.connect(draft)) {
                                    draft = ""
                                    useAndSync()
                                }
                            } finally {
                                working = false
                            }
                        }
                    },
                ) {
                    Text(if (working || status.isBusy) "Connecting…" else "Connect")
                }
                TextButton(onClick = { runCatching { uriHandler.openUri("https://publicmetadb.com") } }) {
                    Text("Get an API key")
                }
            }
        }
        status.errorMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
