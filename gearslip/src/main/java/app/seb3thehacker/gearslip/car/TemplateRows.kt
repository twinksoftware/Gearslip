package app.seb3thehacker.gearslip.car

import androidx.compose.ui.res.stringResource
import app.seb3thehacker.gearslip.R
import app.seb3thehacker.gearslip.car.theme.*
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.foundation.Image
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import app.seb3thehacker.gearslip.host.selected
import app.seb3thehacker.gearslip.host.checkedChanged
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.Pane
import androidx.car.app.model.Row as CarRow
import androidx.car.app.model.Template
import app.seb3thehacker.gearslip.host.click
import app.seb3thehacker.gearslip.host.color
import app.seb3thehacker.gearslip.host.image
import app.seb3thehacker.gearslip.host.isDrawable
import app.seb3thehacker.gearslip.host.standardType
import app.seb3thehacker.gearslip.host.text

// Lists, grids, panes and rows of templated apps. Split out of TemplateParts.kt.

@Composable
internal fun PaneTitle(title: String) {
    if (title.isEmpty()) return
    Text(
        title,
        style = ChromeType.title,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

@Composable
internal fun ItemListColumn(
    single: ItemList?,
    sectioned: List<ItemList>? = null,
    modifier: Modifier = Modifier,
) {
    val rows = buildList {
        single?.items?.filterIsInstance<CarRow>()?.let(::addAll)
        sectioned?.forEach { list -> addAll(list.items.filterIsInstance<CarRow>()) }
    }
    if (rows.isEmpty()) {
        val message = single?.noItemsMessage.text()
        if (message.isNotEmpty()) Text(message, Modifier.padding(14.dp), style = ChromeType.body)
        return
    }
    // A list the app made selectable is a single-choice list: one option chosen, shown as a radio.
    val onSelected = single?.onSelectedDelegate
    val chosen = single?.selectedIndex ?: -1
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 8.dp)) {
        itemsIndexed(rows) { index, row ->
            RowItem(row, onSelected?.let { RowSelection(index == chosen) { it.selected(index) } })
        }
    }
}

/** A grid of tappable tiles - what apps use for a home screen beside the map. Icons here are as
 * often a small menu glyph (Organic Maps' Search, say) as real cover art, so the tile stays
 * modest; [GridTile]'s own default is for the shelf case, where every tile is a photo. */
@Composable
internal fun GridItems(list: ItemList?, modifier: Modifier = Modifier) {
    val items = list?.items?.filterIsInstance<GridItem>().orEmpty()
    if (items.isEmpty()) return
    LazyVerticalGrid(
        columns = GridCells.Adaptive(84.dp),
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(items) { item -> GridTile(item, iconSize = 36.dp) }
    }
}

/**
 * One cover-art tile: an app's own icon over a title and optional subtitle. Shared by
 * [GridItems] (a whole screen of tiles, [iconSize] scaled down there to suit a plain menu glyph)
 * and a section's horizontal shelf, which carries the same [GridItem] type but scrolls sideways
 * instead of wrapping into a grid and wants its full-size cover art.
 */
@Composable
internal fun GridTile(item: GridItem, modifier: Modifier = Modifier, iconSize: androidx.compose.ui.unit.Dp = 144.dp) {
    Column(
        modifier
            .clickable { item.onClickDelegate.click("grid item") }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CarGlyph(item.image, Modifier.size(iconSize))
        Spacer(Modifier.height(6.dp))
        Text(
            item.title.text(),
            style = ChromeType.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val subtitle = item.text.text()
        if (subtitle.isNotEmpty()) {
            Text(
                subtitle,
                style = ChromeType.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * [autoStart] is for a pane floating over a map - a place or a route the app is proposing - where
 * a single action counts itself down rather than waiting to be pressed, on the theory that a
 * driver glancing at the car screen has already decided. See [autoStartLead] for when: a pane
 * offering a real choice (Start vs. something else) always waits to be tapped.
 */
/**
 * The action that counts itself down, if the pane is really proposing one thing: its only action,
 * or the app's primary one when everything beside it is an icon with no words (Waze's "Go now"
 * next to its routes button). Two worded choices ("New drive", "Add a stop") wait for a tap.
 */
internal fun autoStartLead(actions: List<Action>): Action? {
    actions.singleOrNull()?.let { return it }
    val primary = actions.filter { it.flags and Action.FLAG_PRIMARY != 0 }.singleOrNull() ?: return null
    return primary.takeIf { actions.all { it === primary || it.title.text().isBlank() } }
}

@Composable
internal fun PaneRows(pane: Pane?, modifier: Modifier = Modifier, autoStart: Boolean = false) {
    // Some apps pad a pane with a row of blank strings; drawn, it is just a gap.
    val rows = pane?.rows.orEmpty().filterNot { it.isBlank() }
    val actions = pane?.actions.orEmpty().filter { it.isDrawable() }
    val card = PlaceCard.of(rows)
    Column(modifier) {
        card.heading?.let { PlaceHeading(it) }
        if (card.buttons.isNotEmpty()) RowButtons(card.buttons, Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
        val lead = if (autoStart) autoStartLead(actions) else null
        if (lead != null) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AutoStartButton(lead, Modifier.weight(1f))
                actions.filter { it !== lead }.forEach { ActionButton(it, large = true, modifier = Modifier.weight(1f)) }
            }
        } else {
            ActionRow(actions, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), large = true)
        }
        if (card.heading == null && card.buttons.isEmpty()) {
            card.details.forEach { RowItem(it, large = true) }
        } else {
            card.details.forEach { DetailRow(it, card.heading?.title?.text().orEmpty()) }
        }
    }
}

/**
 * A pane split the way a place page reads: a name on top, the things to do with it as buttons,
 * then the facts. Apps send all three as plain rows, so the split is worked out from the rows'
 * shape alone. A pane that doesn't fit the shape - all of it tappable, like a menu - gets no
 * heading and no buttons, and draws exactly as a plain list of rows.
 */
private class PlaceCard(val heading: CarRow?, val buttons: List<CarRow>, val details: List<CarRow>) {
    companion object {
        fun of(rows: List<CarRow>): PlaceCard {
            // A short tappable row with an icon and nothing under it is a button in all but name:
            // MAPS.ME's "Let's go" and "Save".
            val buttons = rows.filter {
                it.onClickDelegate != null && it.toggle == null && it.image != null &&
                    it.texts.orEmpty().isEmpty() && it.title.text().length <= 24
            }.takeIf { it.size in 1..4 && it.size < rows.size }.orEmpty()
            // A first row that can't be tapped and has no icon is the place's name and kind.
            val heading = rows.firstOrNull()?.takeIf {
                buttons.isNotEmpty() && it.onClickDelegate == null && it.image == null && it.toggle == null
            }
            return PlaceCard(heading, buttons, rows - buttons.toSet() - listOfNotNull(heading).toSet())
        }
    }
}

@Composable
private fun PlaceHeading(row: CarRow) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
        Text(row.title.text(), style = ChromeType.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
        row.texts.orEmpty().take(2).forEach {
            Text(it.text(), style = ChromeType.body, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Button-shaped rows side by side, the first one filled as the thing most drivers came to do. */
@Composable
private fun RowButtons(rows: List<CarRow>, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEachIndexed { i, row ->
            GsIconBox(
                onClick = { row.onClickDelegate.click("row") },
                modifier = Modifier.weight(1f),
                colors = if (i == 0) GsColors(scheme.primary, scheme.onPrimary) else GsColors(scheme.surfaceContainerHighest, scheme.onSurface),
                enabled = row.isEnabled,
                shape = RoundedCornerShape(24.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CarGlyph(row.image, Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(row.title.text(), style = ChromeType.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * One fact about a place, tighter than a list row: a small label over its value, or just the
 * value when the row has no label. A phone number gets a Call button that asks first.
 */
@Composable
private fun DetailRow(row: CarRow, placeName: String) {
    val context = LocalContext.current
    val texts = row.texts.orEmpty().map { it.text() }.filter { it.isNotBlank() }
    val label = row.title.text().takeIf { texts.isNotEmpty() }
    val values = texts.ifEmpty { listOf(row.title.text()) }
    val number = values.firstOrNull { it.isPhoneNumber() }
    val canCall = number != null &&
        context.checkSelfPermission(android.Manifest.permission.CALL_PHONE) == android.content.pm.PackageManager.PERMISSION_GRANTED
    Row(
        Modifier
            .fillMaxWidth()
            .let { if (row.onClickDelegate != null && row.isEnabled) it.clickable { row.onClickDelegate.click("row") } else it }
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        row.image?.let {
            CarGlyph(it, Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            label?.let {
                Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            values.take(3).forEach {
                Text(it, style = ChromeType.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (canCall) {
            Spacer(Modifier.width(8.dp))
            GsIconBox(
                onClick = { CallPrompt.ask(placeName.ifBlank { number!! }, number!!) },
                colors = GsColors(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary),
                shape = RoundedCornerShape(24.dp),
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Icon(Icons.Filled.Call, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.dialer_call), style = ChromeType.body)
                }
            }
        }
        ActionRow(row.actions.orEmpty())
    }
}

/** A line that is only a phone number: digits with the usual separators, 7 to 15 digits long. */
private fun String.isPhoneNumber(): Boolean {
    val t = trim()
    if (!Regex("""^\+?[0-9 ().\-]+$""").matches(t)) return false
    return t.count { it.isDigit() } in 7..15
}

@Composable
internal fun RowItem(row: CarRow, selection: RowSelection? = null, large: Boolean = false) {
    val toggle = row.toggle
    // Shown at once and corrected when the app answers with its own template, so the switch
    // never lags a tap; keyed on what the app last said so its answer wins.
    var checked by remember(toggle?.isChecked) { mutableStateOf(toggle?.isChecked == true) }
    val clickable = row.isEnabled && (toggle != null || selection != null || row.onClickDelegate != null)
    Row(
        Modifier
            .fillMaxWidth()
            .let {
                if (!clickable) it else it.clickable {
                    when {
                        toggle != null -> {
                            checked = !checked
                            toggle.onCheckedChangeDelegate.checkedChanged(checked)
                        }
                        selection != null -> selection.onPick()
                        else -> row.onClickDelegate.click("row")
                    }
                }
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selection != null) {
            RadioButton(selected = selection.selected, onClick = null)
            Spacer(Modifier.width(12.dp))
        }
        row.image?.let {
            CarGlyph(it, Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
        }
        // large only changes how much room a row gets (more lines, a bigger icon above) - the
        // type itself is the same scale as every other row, so a pane's one row and a plain
        // list's tenth row read as the same kind of text.
        Column(Modifier.weight(1f)) {
            Text(
                row.title.text(),
                style = ChromeType.title,
                maxLines = if (large) 2 else 1,
                overflow = TextOverflow.Ellipsis,
            )
            row.texts.orEmpty().take(if (large) 3 else 2).forEach { line ->
                Text(
                    line.text(),
                    style = ChromeType.body,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (large) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        ActionRow(row.actions.orEmpty())
        if (toggle != null) {
            Spacer(Modifier.width(8.dp))
            Switch(checked = checked, onCheckedChange = null, enabled = row.isEnabled)
        }
    }
}

/** A row's place in a single-choice list: whether it is the chosen one, and what picking it does. */
internal class RowSelection(val selected: Boolean, val onPick: () -> Unit)

/**
 * Whether this colour stands out against the background that [content] was chosen for: at least
 * 3:1, the WCAG minimum for icons. The background is taken as the opposite of the content colour.
 */
internal fun Color.readableOn(content: Color): Boolean {
    val background = if (content.luminanceIsDark()) Color.White else Color(0xFF121212)
    val a = luminance() + 0.05f
    val b = background.luminance() + 0.05f
    return maxOf(a, b) / minOf(a, b) >= 3f
}
