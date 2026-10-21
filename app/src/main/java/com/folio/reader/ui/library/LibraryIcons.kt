package com.folio.reader.ui.library

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Material icons beyond material-icons-core, drawn from their standard 24dp paths. */
internal object LibraryIcons {
    val Stats = icon("BarChart", "M5,9.2h3V19H5V9.2zM10.6,5h2.8v14h-2.8V5zM16.2,13H19v6h-2.8V13z")
    val Collection = icon(
        "Folder",
        "M10,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2h-8l-2,-2z",
    )
    val Flame = icon(
        "Flame",
        "M19.48,12.35c-1.57,-4.08 -7.16,-4.3 -5.81,-10.23c0.1,-0.44 -0.37,-0.78 -0.75,-0.55C9.29,3.71 6.68,8 8.87,13.62" +
            "c0.18,0.46 -0.36,0.89 -0.75,0.59c-1.81,-1.37 -2,-3.34 -1.84,-4.75c0.06,-0.52 -0.62,-0.77 -0.91,-0.34" +
            "C4.69,10.16 4,11.84 4,14.37c0.38,5.6 5.11,7.32 6.81,7.54c2.43,0.31 5.06,-0.14 6.95,-1.87C19.84,18.11 20.6,15.03 19.48,12.35z",
    )
    val StarOutline = icon(
        "StarBorder",
        "M22,9.24l-7.19,-0.62L12,2 9.19,8.63 2,9.24l5.46,4.73L5.82,21 12,17.27 18.18,21l-1.63,-7.03L22,9.24z" +
            "M12,15.4l-3.76,2.27 1,-4.28 -3.32,-2.88 4.38,-0.38L12,6.1l1.71,4.04 4.38,0.38 -3.32,2.88 1,4.28L12,15.4z",
    )

    val Grid = icon(
        "GridView",
        "M3,3v8h8V3H3zM9,9H5V5h4V9zM3,13v8h8v-8H3zM9,19H5v-4h4V19zM13,3v8h8V3H13zM19,9h-4V5h4V9zM13,13v8h8v-8H13zM19,19h-4v-4h4V19z",
    )
    val Shelf = icon("Shelf", "M4,4h3v13H4zM8,6h3v11H8zM12.3,4.9l2.9,-0.78 3.4,12.6 -2.9,0.78zM2,18h20v2H2z")

    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.Black))
            .build()
}
