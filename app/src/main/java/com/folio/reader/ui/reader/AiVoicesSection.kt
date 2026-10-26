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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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

/** "Bella (US woman)" as a name and what it is. */
internal fun splitSpeaker(label: String): Pair<String, String?> =
    label.substringBefore(" (") to label.substringAfter(" (", "").removeSuffix(")").ifBlank { null }

/**
 * The AI voices part of the voice picker: every voice of each downloaded model, ready to tap,
 * then the models still to download.
 */
@Composable
internal fun AiVoicePicker(
    engine: String?,
    voice: String?,
    aiError: String?,
    /** Chooses a voice; [hear] plays a sample of it once it's ready. */
    onPick: (engine: String?, voice: String?, hear: Boolean) -> Unit,
) {
    val context = LocalContext.current
    val installedIds by remember { AiVoices.installed(context) }.collectAsStateWithLifecycle()
    val progress by AiVoices.progress.collectAsStateWithLifecycle()
    val failed by AiVoices.failed.collectAsStateWithLifecycle()
    val installed = installedIds.orEmpty()
    // Voices downloaded from here are switched to as soon as they're ready.
    val started = remember { mutableStateListOf<String>() }
    LaunchedEffect(installed) {
        started.filter { it in installed }.forEach { id ->
            started.remove(id)
            AiVoices.models.firstOrNull { it.id == id }?.let { onPick(it.engineKey, "0", true) }
        }
    }

    SectionTitle("AI voices", "The most natural. Made on your phone, so they work offline. English only.")
    if (!AiVoices.supported) {
        Text(
            "AI voices need a phone with a 64-bit processor.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    if (aiError != null) {
        Text(aiError, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }

    val have = AiVoices.models.filter { it.id in installed }
    have.forEach { model ->
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    model.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    if (engine == model.engineKey) onPick(null, null, false)
                    AiVoices.delete(context, model)
                }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove ${model.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val chosen = if (engine == model.engineKey) voice?.toIntOrNull() ?: 0 else -1
            model.speakers.forEachIndexed { i, speaker ->
                val (name, what) = splitSpeaker(speaker)
                PickRow(name, what, selected = i == chosen) { onPick(model.engineKey, i.toString(), true) }
            }
        }
    }

    val more = AiVoices.models.filter { it.id !in installed }
    if (more.isNotEmpty()) {
        Text(
            if (have.isEmpty()) "Download one to start" else "More AI voices",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 4.dp),
        )
        Column {
            more.forEach { model ->
                DownloadRow(
                    model = model,
                    progress = progress[model.id],
                    error = failed?.takeIf { it.first == model.id }?.second,
                    onDownload = {
                        started.add(model.id)
                        AiVoices.download(context, model)
                    },
                    onCancel = {
                        started.remove(model.id)
                        AiVoices.cancel(model)
                    },
                )
            }
        }
    }
}

@Composable
internal fun SectionTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DownloadRow(
    model: AiVoiceModel,
    progress: AiVoices.Progress?,
    error: String?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(model.name, style = MaterialTheme.typography.bodyLarge)
            Text(model.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when {
                progress != null -> {
                    val f = progress.fraction
                    Text(
                        when {
                            progress.unpacking -> "Unpacking…"
                            f != null -> "Downloading ${(f * 100).toInt()}%"
                            else -> "Downloading…"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (f != null) {
                        LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
                    }
                }
                error != null -> Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        Spacer(Modifier.width(8.dp))
        if (progress != null) {
            TextButton(onClick = onCancel) { Text("Cancel") }
        } else {
            TextButton(onClick = onDownload) { Text("Get · ${model.sizeMb} MB") }
        }
    }
}

/** One choice in the voice picker: a check when chosen, tap to choose. */
@Composable
internal fun PickRow(title: String, detail: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = "Chosen", tint = MaterialTheme.colorScheme.primary)
        }
    }
}
