package com.folio.reader.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.folio.reader.audio.AiVoiceModel
import com.folio.reader.audio.AiVoices

/** The AI voices: download one, pick it, or remove it to free space. */
@Composable
internal fun AiVoicesSection(engine: String?, aiError: String?, onEngine: (String?) -> Unit) {
    val context = LocalContext.current
    val installed by remember { AiVoices.installed(context) }.collectAsStateWithLifecycle()
    val progress by AiVoices.progress.collectAsStateWithLifecycle()
    val failed by AiVoices.failed.collectAsStateWithLifecycle()
    // Voices downloaded from here are switched to as soon as they're ready.
    val started = remember { mutableStateListOf<String>() }
    LaunchedEffect(installed) {
        val ready = started.filter { it in installed.orEmpty() }
        ready.forEach { id ->
            started.remove(id)
            AiVoices.models.firstOrNull { it.id == id }?.let { onEngine(it.engineKey) }
        }
    }

    Text("AI voices", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (!AiVoices.supported) {
        Text(
            "AI voices need a phone with a 64-bit processor.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Text(
        "Lifelike voices made on your phone. Download one once and it works offline. English only for now.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (aiError != null) {
        Text(aiError, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
    Column {
        AiVoices.models.forEach { model ->
            AiModelRow(
                model = model,
                installed = model.id in installed.orEmpty(),
                selected = engine == model.engineKey,
                progress = progress[model.id],
                error = failed?.takeIf { it.first == model.id }?.second,
                onSelect = { onEngine(model.engineKey) },
                onDownload = {
                    started.add(model.id)
                    AiVoices.download(context, model)
                },
                onCancel = {
                    started.remove(model.id)
                    AiVoices.cancel(model)
                },
                onDelete = {
                    if (engine == model.engineKey) onEngine(null)
                    AiVoices.delete(context, model)
                },
            )
        }
    }
}

@Composable
private fun AiModelRow(
    model: AiVoiceModel,
    installed: Boolean,
    selected: Boolean,
    progress: AiVoices.Progress?,
    error: String?,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = installed, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect, enabled = installed)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(model.name, style = MaterialTheme.typography.bodyLarge)
            Text(model.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when {
                progress != null -> {
                    val label = when {
                        progress.unpacking -> "Unpacking…"
                        progress.fraction != null -> "Downloading ${(progress.fraction * 100).toInt()}%"
                        else -> "Downloading…"
                    }
                    Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    val f = progress.fraction
                    if (f != null) {
                        LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
                    }
                }
                error != null -> Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        when {
            progress != null -> TextButton(onClick = onCancel) { Text("Cancel") }
            installed -> IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Remove ${model.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> TextButton(onClick = onDownload) { Text("Get · ${model.sizeMb} MB") }
        }
    }
}
