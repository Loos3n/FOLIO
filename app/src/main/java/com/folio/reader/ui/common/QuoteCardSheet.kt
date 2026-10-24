package com.folio.reader.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.folio.reader.data.Highlight
import com.folio.reader.data.QuoteCard

/** Turns a highlight into a picture: pick a look and a shape, then share it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuoteCardSheet(mark: Highlight, title: String, author: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var look by rememberSaveable { mutableStateOf(QuoteCard.Look.Paper) }
    var shape by rememberSaveable { mutableStateOf(QuoteCard.Shape.Tall) }
    val bitmap = remember(mark.id, mark.color, look, shape) {
        QuoteCard.render(mark.text, title, author, mark.color, look, shape)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Quote card", style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
            Image(
                bitmap.asImageBitmap(),
                contentDescription = "Preview of the quote card",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .heightIn(max = 380.dp)
                    .aspectRatio(shape.width.toFloat() / shape.height)
                    .clip(RoundedCornerShape(12.dp)),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuoteCard.Look.entries.forEach { l ->
                    FilterChip(selected = l == look, onClick = { look = l }, label = { Text(l.label) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuoteCard.Shape.entries.forEach { s ->
                    FilterChip(selected = s == shape, onClick = { shape = s }, label = { Text(s.label) })
                }
            }
            Button(onClick = { QuoteCard.share(context, bitmap, title) }, modifier = Modifier.fillMaxWidth()) {
                Text("Share")
            }
        }
    }
}
