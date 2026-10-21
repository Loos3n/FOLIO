@file:OptIn(ExperimentalMaterial3Api::class)

package com.folio.reader.ui.library

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.data.SavedWord
import com.folio.reader.ui.reader.DictionarySheet

/** Every word looked up with Define, with the sentence it came from. Tap one to see its meaning again. */
@Composable
internal fun WordListSheet(
    words: List<SavedWord>,
    onRemove: (SavedWord) -> Unit,
    onDismiss: () -> Unit,
) {
    var alphabetical by rememberSaveable { mutableStateOf(false) }
    var open by remember { mutableStateOf<SavedWord?>(null) }
    val context = LocalContext.current
    val shown = remember(words, alphabetical) { if (alphabetical) words.sortedBy { it.word.lowercase() } else words }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 24.dp)) {
            Text("Word list", style = MaterialTheme.typography.headlineSmall)
            Text(
                if (words.size == 1) "1 word you looked up" else "${words.size} words you looked up",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (words.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !alphabetical, onClick = { alphabetical = false }, label = { Text("Recent") })
                    FilterChip(selected = alphabetical, onClick = { alphabetical = true }, label = { Text("A to Z") })
                }
            }
        }
        if (words.isEmpty()) {
            Text(
                "Words you look up with Define while reading are saved here, with the sentence they came from.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
            )
            return@ModalBottomSheet
        }
        LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 32.dp)) {
            items(shown, key = { it.id }) { w ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { open = w }
                        .padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(w.word, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            if (w.partOfSpeech != null) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    w.partOfSpeech,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontStyle = FontStyle.Italic,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(bottom = 2.dp),
                                )
                            }
                        }
                        if (w.meaning != null) {
                            Text(w.meaning, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                        if (w.sentence.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "“${w.sentence}”",
                                style = MaterialTheme.typography.bodySmall,
                                fontStyle = FontStyle.Italic,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (w.bookTitle.isNotBlank()) {
                            Text(
                                w.bookTitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    IconButton(onClick = { onRemove(w) }) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove ${w.word}", modifier = Modifier.size(20.dp))
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
        }
    }
    open?.let { w ->
        DictionarySheet(
            term = w.word,
            onWebSearch = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode("define ${w.word}"))))
                }
            },
            onDismiss = { open = null },
        )
    }
}
