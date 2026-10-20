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
    /** view_day: the layout switch's "scrolling" choice. */
    val Scroll = icon(
        "Scroll",
        "M2,21h19v-3H2v3zM20,8H3c-0.55,0 -1,0.45 -1,1v6c0,0.55 0.45,1 1,1h17c0.55,0 1,-0.45 1,-1V9c0,-0.55 -0.45,-1 -1,-1zM2,3v3h19V3H2z",
    )
    /** auto_stories: the layout switch's "pages" choice. */
    val Pages = icon(
        "Pages",
        "M19,1l-5,5v11l5,-4.5V1zM1,6v14.65c0,0.25 0.25,0.5 0.5,0.5 0.1,0 0.15,-0.05 0.25,-0.05C3.1,20.45 5.05,20 6.5,20c1.95,0 4.05,0.4 5.5,1.5V6c-1.45,-1.1 -3.55,-1.5 -5.5,-1.5S2.45,4.9 1,6zM23,19.5V6c-0.6,-0.45 -1.25,-0.75 -2,-1v13.5c-1.1,-0.35 -2.3,-0.5 -3.5,-0.5 -1.7,0 -4.15,0.65 -5.5,1.5v2c1.35,-0.85 3.8,-1.5 5.5,-1.5 1.65,0 3.35,0.3 4.75,1.05 0.1,0.05 0.15,0.05 0.25,0.05 0.25,0 0.5,-0.25 0.5,-0.5v-1.1z",
    )
    /** keyboard_double_arrow_down. */
    val AutoScroll = icon("AutoScroll", "M18,6.41L16.59,5L12,9.58L7.41,5L6,6.41l6,6zM18,13l-1.41,-1.41L12,16.17l-4.59,-4.58L6,13l6,6z")

    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.Black))
            .build()
}
