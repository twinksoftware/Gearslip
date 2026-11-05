package app.seb3thehacker.gearslip.car

import androidx.compose.ui.res.stringResource
import app.seb3thehacker.gearslip.R
import android.media.session.PlaybackState
import android.support.v4.media.session.PlaybackStateCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import app.seb3thehacker.gearslip.media.ArtColor
import app.seb3thehacker.gearslip.media.MediaArt
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import app.seb3thehacker.gearslip.car.theme.GsIconBox
import app.seb3thehacker.gearslip.car.theme.GsIconButton
import app.seb3thehacker.gearslip.car.theme.GsSeekBar
import app.seb3thehacker.gearslip.car.theme.GsTone
import app.seb3thehacker.gearslip.car.theme.gsColors
import app.seb3thehacker.gearslip.media.CarMedia
import app.seb3thehacker.gearslip.media.CustomAction
import app.seb3thehacker.gearslip.media.NowPlaying
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/*
 * The pieces every player on the car screen is built from - the media screen, the column beside
 * the map - so a button looks and behaves the same wherever it turns up.
 */

/** The seek bar, with elapsed and total time under it when [showTimes]. */
@Composable
internal fun PlayerProgress(now: NowPlaying, media: CarMedia, modifier: Modifier = Modifier, showTimes: Boolean = true) {
    if (now.durationMs <= 0) return
    var position by remember { mutableLongStateOf(now.currentPosition()) }
    LaunchedEffect(now) {
        while (true) {
            position = now.currentPosition()
            if (!now.playing) break
            delay(500)
        }
    }
    Column(modifier) {
        GsSeekBar(
            fraction = position.toFloat() / now.durationMs,
            onSeek = { media.seek((it * now.durationMs).toLong()) },
            enabled = now.canDo(PlaybackState.ACTION_SEEK_TO),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
        if (showTimes) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatDuration(position), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatDuration(now.durationMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** "1:05" for under a minute past the hour, "1:01:05" once an hour is involved. */
internal fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

/** Previous, play/pause, next. [skip] sizes the outer two; play is [playScale] times bigger. */
@Composable
internal fun TransportRow(now: NowPlaying, media: CarMedia, skip: Dp, modifier: Modifier = Modifier, playScale: Float = 1.25f) {
    val play = skip * playScale
    Row(modifier, horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        GsIconButton(
            MediaIcons.Previous, stringResource(R.string.player_previous), media::previous,
            enabled = now.canDo(PlaybackState.ACTION_SKIP_TO_PREVIOUS), size = skip, iconSize = skip * 0.58f, shape = CircleShape,
        )
        GsIconButton(
            if (now.playing) MediaIcons.Pause else Icons.Filled.PlayArrow,
            stringResource(if (now.playing) R.string.player_pause else R.string.media_play),
            media::togglePlay,
            tone = GsTone.Primary, size = play, iconSize = play * 0.58f, shape = CircleShape,
        )
        GsIconButton(
            MediaIcons.Next, stringResource(R.string.player_next), media::next,
            enabled = now.canDo(PlaybackState.ACTION_SKIP_TO_NEXT), size = skip, iconSize = skip * 0.58f, shape = CircleShape,
        )
    }
}

/**
 * Like, shuffle and repeat, then up to [maxOther] of the app's other buttons, all icon-only. One
 * the app doesn't support stays on screen greyed out, so the row never shifts between apps.
 * [trailing] adds buttons of the caller's own at the end (the side column's lyrics button).
 */
@Composable
internal fun PlayModeRow(
    now: NowPlaying,
    media: CarMedia,
    size: Dp,
    modifier: Modifier = Modifier,
    maxOther: Int = 0,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val modes by media.modes.collectAsState()
    val liked = now.liked
    val shuffleKnown = modes.shuffle != PlaybackStateCompat.SHUFFLE_MODE_INVALID
    val repeatKnown = modes.repeat != PlaybackStateCompat.REPEAT_MODE_INVALID
    val shuffleOn = shuffleKnown && modes.shuffle != PlaybackStateCompat.SHUFFLE_MODE_NONE
    val repeatOn = repeatKnown && modes.repeat != PlaybackStateCompat.REPEAT_MODE_NONE
    val icon = size * 0.52f
    Row(modifier, horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        // An app's own like, shuffle or repeat button shows whether it's on only through its icon,
        // so that icon is drawn, as Android Auto does, and lit when its name says it's on.
        // Gearslip's own stand in for apps that report a heart rating or shuffle and repeat modes.
        val likeAction = now.likeAction
        if (likeAction != null) {
            CustomActionButton(likeAction, media, size, on = liked == true)
        } else {
            GsIconButton(
                if (liked == true) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                stringResource(if (liked == true) R.string.player_unlike else R.string.player_like),
                media::toggleLike,
                enabled = liked != null,
                tone = if (liked == true) GsTone.Primary else GsTone.Neutral,
                size = size, iconSize = icon,
            )
        }
        val shuffleAction = now.shuffleAction
        if (shuffleAction != null) {
            CustomActionButton(shuffleAction, media, size, on = shuffleAction.on)
        } else {
            GsIconButton(
                MediaIcons.Shuffle, stringResource(if (shuffleOn) R.string.player_shuffle_off else R.string.player_shuffle_on),
                media::toggleShuffle,
                enabled = shuffleKnown,
                tone = if (shuffleOn) GsTone.Primary else GsTone.Neutral,
                size = size, iconSize = icon,
            )
        }
        val repeatAction = now.repeatAction
        if (repeatAction != null) {
            CustomActionButton(repeatAction, media, size, on = repeatAction.on)
        } else {
            GsIconButton(
                if (modes.repeat == PlaybackStateCompat.REPEAT_MODE_ONE) MediaIcons.RepeatOne else MediaIcons.Repeat,
                stringResource(R.string.player_repeat),
                media::cycleRepeat,
                enabled = repeatKnown,
                tone = if (repeatOn) GsTone.Primary else GsTone.Neutral,
                size = size, iconSize = icon,
            )
        }
        now.otherActions.take(maxOther).forEach { CustomActionButton(it, media, size) }
        trailing()
    }
}

/**
 * One of the app's own buttons, drawn with the app's own icon - it lives in the app's package,
 * not Gearslip's, so it's loaded from there. Its name stands in if the icon won't load.
 */
@Composable
private fun CustomActionButton(action: CustomAction, media: CarMedia, size: Dp, on: Boolean = false) {
    val icon = rememberActionIcon(action)
    GsIconBox(
        onClick = { media.custom(action) },
        modifier = Modifier.size(size),
        colors = gsColors(if (on) GsTone.Primary else GsTone.Neutral),
    ) {
        if (icon != null) {
            Icon(icon, action.name, Modifier.size(size * 0.52f))
        } else {
            Text(action.name, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun rememberActionIcon(action: CustomAction): ImageBitmap? {
    val context = LocalContext.current
    val app by CarServices.mediaApp.collectAsState()
    val pkg = app?.component?.packageName
    val icon by produceState<ImageBitmap?>(null, pkg, action.icon) {
        value = if (pkg == null || action.icon == 0) null else withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getResourcesForApplication(pkg)
                    .getDrawable(action.icon, null).toBitmap(96, 96).asImageBitmap()
            }.getOrNull()
        }
    }
    return icon
}

/** The track's art, from the session's bitmap or, failing that, its art URI. */
@Composable
internal fun rememberArt(now: NowPlaying): android.graphics.Bitmap? {
    val context = LocalContext.current
    val art by produceState(now.art, now.art, now.artUri) { value = now.art ?: MediaArt.load(context, now.artUri) }
    return art
}

/**
 * Three soft pools of the cover's colours: top left, top right and the middle, read off a 3x3
 * shrink of it. Still, and redrawn only while a new song's colours fade in, so it costs the video
 * stream nothing between songs. Fainter on a light card, where colour shows more. [wide] is for
 * a whole screen: the pools gather on its left, behind the cover, and reach less far across it.
 */
@Composable
internal fun AlbumGlow(art: android.graphics.Bitmap?, modifier: Modifier, wide: Boolean = false) {
    val spots by produceState<List<Color>>(emptyList(), art) {
        value = art?.let { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { glowColours(it) } }.orEmpty()
    }
    val strength = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) 0.30f else 0.18f
    val fade = tween<Color>(600)
    val first by androidx.compose.animation.animateColorAsState(spots.getOrNull(0)?.copy(alpha = strength) ?: Color.Transparent, fade, label = "glow")
    val second by androidx.compose.animation.animateColorAsState(spots.getOrNull(1)?.copy(alpha = strength) ?: Color.Transparent, fade, label = "glow")
    val third by androidx.compose.animation.animateColorAsState(spots.getOrNull(2)?.copy(alpha = strength * 0.8f) ?: Color.Transparent, fade, label = "glow")
    androidx.compose.foundation.Canvas(modifier) {
        fun pool(colour: Color, x: Float, y: Float, reach: Float) {
            if (colour.alpha <= 0f) return
            drawRect(
                androidx.compose.ui.graphics.Brush.radialGradient(
                    listOf(colour, Color.Transparent),
                    center = androidx.compose.ui.geometry.Offset(size.width * x, size.height * y),
                    radius = size.width * reach,
                ),
            )
        }
        if (wide) {
            pool(third, 0.22f, 0.8f, 0.35f)
            pool(first, 0.1f, 0.25f, 0.42f)
            pool(second, 0.3f, 0.45f, 0.38f)
        } else {
            pool(third, 0.5f, 0.5f, 0.85f)
            pool(first, 0.2f, 0.1f, 0.9f)
            pool(second, 0.85f, 0.22f, 0.8f)
        }
    }
}

/** The cover's colours at its top left, top right and middle. */
private fun glowColours(art: android.graphics.Bitmap): List<Color> {
    // A hardware bitmap can't be scaled or read on the CPU, so copy it out first.
    val readable = if (art.config == android.graphics.Bitmap.Config.HARDWARE) art.copy(android.graphics.Bitmap.Config.ARGB_8888, false) else art
    val small = android.graphics.Bitmap.createScaledBitmap(readable ?: return emptyList(), 3, 3, true)
    return listOf(small.getPixel(0, 0), small.getPixel(2, 0), small.getPixel(1, 1)).map { Color(it).copy(alpha = 1f) }
}
