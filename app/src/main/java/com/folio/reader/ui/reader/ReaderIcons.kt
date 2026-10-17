package com.folio.reader.ui.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The few Material icons the reader needs beyond material-icons-core, drawn from their
 * standard 24dp paths so the app doesn't pull in the multi-megabyte extended icon set.
 */
internal object ReaderIcons {
    val Bookmark = icon("Bookmark", "M17,3H7c-1.1,0 -1.99,0.9 -1.99,2L5,21l7,-3 7,3V5c0,-1.1 -0.9,-2 -2,-2z")
    val BookmarkBorder = icon(
        "BookmarkBorder",
        "M17,3H7c-1.1,0 -1.99,0.9 -1.99,2L5,21l7,-3 7,3V5c0,-1.1 -0.9,-2 -2,-2zM17,18l-5,-2.18L7,18V5h10v13z",
    )
    val Headphones = icon(
        "Headphones",
        "M12,1c-4.97,0 -9,4.03 -9,9v7c0,1.66 1.34,3 3,3h3v-8H5v-2c0,-3.87 3.13,-7 7,-7s7,3.13 7,7v2h-4v8h3c1.66,0 3,-1.34 3,-3v-7c0,-4.97 -4.03,-9 -9,-9z",
    )
    val Pause = icon("Pause", "M6,19h4V5H6v14zM14,5v14h4V5h-4z")
    val Play = icon("Play", "M8,5v14l11,-7z")

    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.Black))
            .build()
}
