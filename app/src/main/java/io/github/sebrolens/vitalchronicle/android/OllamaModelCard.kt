package io.github.sebrolens.vitalchronicle.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun OllamaModelCard(
    model: OllamaModelSpec,
    state: OllamaInstallState,
    vm: VitalViewModel,
) {
    val recommended = model.id == vm.recommendedOllamaModel.id
    val selected = model.id == vm.selectedOllamaModelId && state is OllamaInstallState.Installed
    val hardwareFit = !vm.hardware.lowRamDevice && vm.hardware.ramGb >= model.minimumRamGb
    val progress = when (state) {
        is OllamaInstallState.Downloading -> state.downloadedBytes.toFloat() / state.totalBytes
        is OllamaInstallState.Paused -> state.downloadedBytes.toFloat() / state.totalBytes
        is OllamaInstallState.Failed -> state.downloadedBytes.toFloat() / state.totalBytes
        is OllamaInstallState.Verifying, is OllamaInstallState.Installed -> 1f
        OllamaInstallState.NotInstalled -> 0f
    }.coerceIn(0f,1f)

    Card(
        colors=if(selected) CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.secondaryContainer)
        else CardDefaults.cardColors(),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(model.displayName,fontWeight=FontWeight.Bold)
                    Text("${model.id} · ${model.parameterCount} · ${formatBytes(model.downloadBytes)}",style=MaterialTheme.typography.bodySmall)
                }
                if(recommended) SuggestionChip(onClick={},label={Text("Recommended")})
            }
            Row(horizontalArrangement=Arrangement.spacedBy(7.dp),verticalAlignment=Alignment.CenterVertically) {
                if(model.supportsThinking) AssistChip(onClick={},label={Text("Thinking")},leadingIcon={Icon(Icons.Default.Psychology,null)})
                Text(
                    if(hardwareFit) "Fits this phone" else "Needs about ${model.minimumRamGb} GB RAM",
                    style=MaterialTheme.typography.labelMedium,
                    color=if(hardwareFit) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }

            when(state) {
                OllamaInstallState.NotInstalled -> Text("Not downloaded",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                is OllamaInstallState.Paused -> Text("Paused · ${formatBytes(state.downloadedBytes)} of ${formatBytes(state.totalBytes)}",style=MaterialTheme.typography.bodySmall)
                is OllamaInstallState.Downloading -> Text("Downloading · ${(progress*100).toInt()}% · ${formatBytes(state.downloadedBytes)} of ${formatBytes(state.totalBytes)}",style=MaterialTheme.typography.bodySmall)
                is OllamaInstallState.Verifying -> Text("Download complete · verifying SHA-256…",style=MaterialTheme.typography.bodySmall)
                is OllamaInstallState.Installed -> Text("Installed and verified",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.primary)
                is OllamaInstallState.Failed -> Text(state.message,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
            }

            if(progress > 0f && state !is OllamaInstallState.Installed) {
                if(state is OllamaInstallState.Verifying) LinearProgressIndicator(Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress={progress},modifier=Modifier.fillMaxWidth())
            }

            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End,verticalAlignment=Alignment.CenterVertically) {
                when(state) {
                    is OllamaInstallState.Downloading, is OllamaInstallState.Verifying -> OutlinedButton(onClick={vm.cancelOllamaDownload(model.id)}) {
                        Icon(Icons.Default.Pause,null); Spacer(Modifier.width(7.dp)); Text("Pause")
                    }
                    is OllamaInstallState.Installed -> {
                        IconButton(onClick={vm.deleteOllamaModel(model.id)},enabled=!vm.busy) { Icon(Icons.Default.DeleteOutline,"Delete model") }
                        Spacer(Modifier.width(6.dp))
                        Button(onClick={vm.selectOllamaModel(model.id)},enabled=!selected) {
                            Icon(Icons.Default.CheckCircle,null); Spacer(Modifier.width(7.dp)); Text(if(selected) "Selected" else "Use model")
                        }
                    }
                    else -> Button(onClick={vm.downloadOllamaModel(model.id)}) {
                        Icon(Icons.Default.CloudDownload,null); Spacer(Modifier.width(7.dp)); Text(if(progress>0f) "Resume" else "Download")
                    }
                }
            }
        }
    }
}
