package app.seb3thehacker.gearslip.car

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle

// The CarPlay-like left rail and its persistent player, in the Nothing OS visual language:
// black canvas, hairline dividers, blocky monochrome glyphs, tracked uppercase labels and one
// red accent. No cards, no depth, no decoration beyond a 2dp active marker.

/** The seven destinations of the rail; [onClick] is the screen (or action) a tap performs. */
private data class RailEntry(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/**
 * A vertical app rail down the left edge of the car screen.
 *
 * The active destination follows [CarNavigator.current]; SAVE is an action (the screensaver) and
 * so is never latched. The rail is 104.dp wide at normal text scale and grows gently with the
 * ambient font scale, as the rest of the car UI's tracked labels do.
 */
@Composable
fun CarNothingRail(navigator: CarNavigator, modifier: Modifier = Modifier) {
    val railWidth = 104.dp * LocalDensity.current.fontScale.coerceIn(1f, 1.3f)
    val active = railActive(navigator.current)
    val entries = listOf(
        RailEntry("MAPS", RailIcons.Maps) { navigator.home() },
        RailEntry("PLACES", RailIcons.Places) { navigator.placeSearch() },
        RailEntry("MUSIC", RailIcons.Music) { navigator.firstPartyMusic() },
        RailEntry("APPS", RailIcons.Apps) { navigator.apps() },
        RailEntry("WEATHER", RailIcons.Weather) { navigator.weather() },
        RailEntry("CAR", RailIcons.Car) { navigator.vehicleData() },
        // The resting screen is an action, not a destination: show it without moving the trail.
        RailEntry("SAVE", RailIcons.Save) { CarScreensaver.show() },
    )
    Column(
        modifier
            .width(railWidth)
            .fillMaxHeight()
            .background(Color.Black),
    ) {
        entries.forEachIndexed { index, entry ->
            RailButton(entry, active = index == active)
        }
    }
}

/** Which rail slot the current car screen belongs to; null when it belongs to none (SAVE included). */
private fun railActive(screen: CarScreen): Int? = when (screen) {
    CarScreen.Home -> 0
    CarScreen.PlaceSearch -> 1
    CarScreen.FirstPartyMusic, CarScreen.FirstPartyPlayer -> 2
    CarScreen.Apps, CarScreen.Browse, CarScreen.Settings, is CarScreen.App -> 3
    CarScreen.Weather, CarScreen.Dashboard -> 4
    CarScreen.VehicleData -> 5
    else -> null
}

/** One rail destination: a blocky glyph over a tracked label, with a red bar when active. */
@Composable
private fun RailButton(entry: RailEntry, active: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val accent = scheme.error
    val content = if (active) scheme.onSurface else scheme.onSurfaceVariant
    Box(
        Modifier
            .fillMaxWidth()
            .height(88.dp)
            .background(if (active) accent.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(onClick = entry.onClick),
    ) {
        if (active) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(accent),
            )
        }
        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(entry.icon, contentDescription = null, tint = content, modifier = Modifier.size(24.dp))
            Text(
                entry.label,
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 0.12.em,
                color = content,
                maxLines = 1,
            )
        }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(1.dp)
                .background(scheme.outline),
        )
    }
}

/** Small monochrome glyphs drawn from square paths, so the rail needs no icon dependency. */
private object RailIcons {
    val Maps = vector("Maps", "M3,3h18v18H3zM6,6v12h12V6zM10,10h4v4h-4z", PathFillType.EvenOdd)
    val Places = vector("Places", "M4,4h7v7H4zM13,4h7v7h-7zM4,13h7v7H4zM13,13h7v7h-7z")
    val Music = vector("Music", "M6,16h5v5H6zM11,6h3v11h-3zM14,6h5v3h-5z")
    val Apps = vector(
        "Apps",
        "M4,4h4v4H4zM10,4h4v4h-4zM16,4h4v4h-4z" +
            "M4,10h4v4H4zM10,10h4v4h-4zM16,10h4v4h-4z" +
            "M4,16h4v4H4zM10,16h4v4h-4zM16,16h4v4h-4z",
    )
    val Weather = vector("Weather", "M9,9h6v6H9zM10,2h4v4h-4zM10,18h4v4h-4zM2,10h4v4H2zM18,10h4v4h-4z")
    val Car = vector("Car", "M4,12h16v6H4zM7,7h10v5H7zM6,18h3v2H6zM15,18h3v2h-3z")
    val Save = vector("Save", "M4,4h16v16H4zM8,8h8v8H8zM10,10h4v4h-4z", PathFillType.EvenOdd)

    private fun vector(name: String, path: String, fillType: PathFillType = PathFillType.NonZero) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(addPathNodes(path), pathFillType = fillType, fill = SolidColor(Color.Black))
            .build()
}

/**
 * The compact player strip above the content: cover, track or "NOTHING PLAYING", then play/pause
 * and next. Tapping the words opens the full first-party player; no rounded surfaces or shadows.
 */
@Composable
fun NothingBottomPlayer(modifier: Modifier = Modifier) {
    val media = CarServices.media
    val navigator = LocalCarNavigator.current
    val now by media.now.collectAsStateWithLifecycle()
    val art = rememberArt(now)
    val scheme = MaterialTheme.colorScheme
    val hasTrack = now.title.isNotEmpty()
    val content = scheme.onSurface
    val muted = scheme.onSurfaceVariant

    Box(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(Color.Black),
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.outline))
            Row(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier
                        .size(36.dp)
                        .background(scheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    if (art != null) {
                        Image(
                            art.asImageBitmap(),
                            contentDescription = null,
                            Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Box(Modifier.size(12.dp).background(muted.copy(alpha = 0.35f)))
                    }
                }
                Column(
                    Modifier
                        .weight(1f)
                        .clickable { navigator.firstPartyPlayer() }
                        .then(if (hasTrack) Modifier else Modifier.alpha(0.5f)),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        if (hasTrack) now.title.uppercase() else "NOTHING PLAYING",
                        style = MaterialTheme.typography.labelMedium,
                        letterSpacing = 0.12.em,
                        fontWeight = if (hasTrack) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (hasTrack) content else muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (hasTrack) {
                        Text(
                            now.artist.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            letterSpacing = 0.12.em,
                            color = muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = { media.togglePlay() }, enabled = hasTrack) {
                    Icon(
                        if (now.playing) MediaIcons.Pause else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = if (hasTrack) content else muted,
                        modifier = Modifier.size(22.dp),
                    )
                }
                IconButton(onClick = { media.next() }, enabled = hasTrack) {
                    Icon(
                        MediaIcons.Next,
                        contentDescription = null,
                        tint = if (hasTrack) content else muted,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}
