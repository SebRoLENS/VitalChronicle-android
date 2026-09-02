package io.github.sebrolens.vitalchronicle.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun OllamaModelCard(
    model: OllamaModelSpec,
    state: OllamaInstallState,
    vm: VitalViewModel,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        // The AtomicBoolean guard in the catalog makes simultaneous cards share
        // one refresh request when this screen is opened.
        AccelerationDriverCatalog.refreshInBackground(context)
    }

    val recommended = model.id == vm.recommendedOllamaModel.id
    val selected = model.id == vm.selectedOllamaModelId && state is OllamaInstallState.Installed
    val hardwareFit = !vm.hardware.lowRamDevice && vm.hardware.ramGb >= model.minimumRamGb
    // Reading the SnapshotStateList-backed catalog in composition makes the card
    // update automatically when a newer remote driver catalog is accepted.
    val driverStatuses = AccelerationDriverCatalog.ggufStatuses(vm.hardware)
    val activeDriver = driverStatuses.firstOrNull { it.available }
        ?: vm.hardware.ggufDriverStatus
    val progress = when (state) {
        is OllamaInstallState.Downloading -> state.downloadedBytes.toFloat() / state.totalBytes
        is OllamaInstallState.Paused -> state.downloadedBytes.toFloat() / state.totalBytes
        is OllamaInstallState.Failed -> state.downloadedBytes.toFloat() / state.totalBytes
        is OllamaInstallState.Verifying, is OllamaInstallState.Installed -> 1f
        OllamaInstallState.NotInstalled -> 0f
    }.coerceIn(0f, 1f)

    Card(
        colors = if (selected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        else CardDefaults.cardColors(),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(model.displayName, fontWeight = FontWeight.Bold)
                    Text("${model.id} · ${model.parameterCount} · ${formatBytes(model.downloadBytes)}", style = MaterialTheme.typography.bodySmall)
                }
                if (recommended) SuggestionChip(onClick = {}, label = { Text("Recommended") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                if (model.supportsThinking) AssistChip(onClick = {}, label = { Text("Thinking") }, leadingIcon = { Icon(Icons.Default.Psychology, null) })
                AssistChip(
                    onClick = {},
                    label = { Text("${activeDriver.spec.kind.name} · ${activeDriver.availabilityLabel}") },
                )
            }
            Text(
                "Preferred backend · ${activeDriver.spec.title} · ${activeDriver.scopeLabel}",
                style = MaterialTheme.typography.bodySmall,
                color = if (activeDriver.available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (hardwareFit) "Fits this phone" else "Needs about ${model.minimumRamGb} GB RAM",
                style = MaterialTheme.typography.labelMedium,
                color = if (hardwareFit) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )

            Text("Acceleration drivers", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
            driverStatuses.take(MAX_VISIBLE_DRIVER_ROWS).forEach { driver ->
                val marker = when {
                    driver.available -> "✓"
                    driver.requiresAppUpdate -> "↻"
                    else -> "○"
                }
                val statusColor = when {
                    driver.available -> MaterialTheme.colorScheme.primary
                    driver.requiresAppUpdate -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        "$marker ${driver.spec.title} · ${driver.scopeLabel} · ${driver.availabilityLabel}",
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor,
                        fontWeight = if (driver.available) FontWeight.Medium else FontWeight.Normal,
                    )
                    if (!driver.available || driver.requiresAppUpdate) {
                        Text(
                            driver.reason,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Text(
                if (activeDriver.spec.kind == AcceleratorKind.CPU) {
                    "This GGUF model will use ${activeDriver.spec.title}. VitalChronicle will automatically prefer a compatible GPU/NPU backend when one becomes available."
                } else {
                    "This GGUF model will prefer ${activeDriver.spec.title}. If accelerated model loading fails, llama.cpp automatically retries on CPU."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when (state) {
                OllamaInstallState.NotInstalled -> Text("Not downloaded", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                is OllamaInstallState.Paused -> Text("Paused · ${formatBytes(state.downloadedBytes)} of ${formatBytes(state.totalBytes)}", style = MaterialTheme.typography.bodySmall)
                is OllamaInstallState.Downloading -> Text("Downloading · ${(progress * 100).toInt()}% · ${formatBytes(state.downloadedBytes)} of ${formatBytes(state.totalBytes)}", style = MaterialTheme.typography.bodySmall)
                is OllamaInstallState.Verifying -> Text("Download complete · verifying SHA-256…", style = MaterialTheme.typography.bodySmall)
                is OllamaInstallState.Installed -> Text("Installed and verified", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                is OllamaInstallState.Failed -> Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            if (progress > 0f && state !is OllamaInstallState.Installed) {
                if (state is OllamaInstallState.Verifying) LinearProgressIndicator(Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                when (state) {
                    is OllamaInstallState.Downloading, is OllamaInstallState.Verifying -> OutlinedButton(onClick = { vm.cancelOllamaDownload(model.id) }) {
                        Icon(Icons.Default.Pause, null); Spacer(Modifier.width(7.dp)); Text("Pause")
                    }
                    is OllamaInstallState.Installed -> {
                        IconButton(onClick = { vm.deleteOllamaModel(model.id) }, enabled = !vm.busy) { Icon(Icons.Default.DeleteOutline, "Delete model") }
                        Spacer(Modifier.width(6.dp))
                        Button(onClick = { vm.selectOllamaModel(model.id) }, enabled = !selected) {
                            Icon(Icons.Default.CheckCircle, null); Spacer(Modifier.width(7.dp)); Text(if (selected) "Selected" else "Use model")
                        }
                    }
                    else -> Button(onClick = { vm.downloadOllamaModel(model.id) }) {
                        Icon(Icons.Default.CloudDownload, null); Spacer(Modifier.width(7.dp)); Text(if (progress > 0f) "Resume" else "Download")
                    }
                }
            }
        }
    }
}

private const val MAX_VISIBLE_DRIVER_ROWS = 4
