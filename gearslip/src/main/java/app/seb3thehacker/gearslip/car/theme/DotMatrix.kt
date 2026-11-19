package app.seb3thehacker.gearslip.car.theme

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color

/**
 * A tiny 7x7 dot-matrix glyph set used on the car player. Kept sparse on purpose: dense patterns
 * read as blocky letters at car-display sizes. Each '#' is one circle.
 */
object DotGlyphs {
    val Back = arrayOf(
        ".......", "...#...", "..##...", ".######", ".######", "..##...", "...#...",
    )
    val Shuffle = arrayOf(
        "#.....#", ".#...#.", "..#.#..", "...#...", "..#.#..", ".#...#.", "#.....#",
    )
    val Repeat = arrayOf(
        ".#####.", "#.....#", "#.....#", "#.....#", "#.....#", "#....##", ".###...",
    )
    val Queue = arrayOf(
        ".#####.", ".......", ".#####.", ".......", ".#####.", ".......", ".......",
    )
    val Lyrics = arrayOf(
        ".#####.", "##...##", "##.#.##", "##...##", "##...##", ".#####.", "..##...",
    )
    val More = arrayOf(
        ".......", ".......", ".......", ".#.#.#.", ".......", ".......", ".......",
    )
    // Bar on the right with a left-pointing triangle: previous.
    val Prev = arrayOf(
        "......#", "....#.#", "...##.#", "..###.#", "...##.#", "....#.#", "......#",
    )
    val Play = arrayOf(
        "#......", "##.....", "###....", "####...", "###....", "##.....", "#......",
    )
    val Pause = arrayOf(
        ".#...#.", ".#...#.", ".#...#.", ".#...#.", ".#...#.", ".#...#.", ".#...#.",
    )
    // Bar on the left with a right-pointing triangle: next.
    val Next = arrayOf(
        "#......", "#.#....", "#.##...", "#.###..", "#.##...", "#.#....", "#......",
    )
}

@Composable
fun DotIcon(
    glyph: Array<String>,
    color: Color,
    modifier: Modifier,
    radiusFactor: Float = 0.30f,
) {
    Canvas(modifier) {
        val cols = glyph[0].length
        val rows = glyph.size
        val px = minOf(size.width / cols, size.height / rows)
        val r = px * radiusFactor
        val ox = (size.width - px * cols) / 2f
        val oy = (size.height - px * rows) / 2f
        for (y in 0 until rows) {
            for (x in 0 until cols) {
                if (glyph[y][x] == '#') {
                    drawCircle(
                        color = color,
                        radius = r,
                        center = Offset(ox + (x + 0.5f) * px, oy + (y + 0.5f) * px),
                    )
                }
            }
        }
    }
}
