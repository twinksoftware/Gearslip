package app.seb3thehacker.gearslip.car

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.seb3thehacker.gearslip.R
import app.seb3thehacker.gearslip.browser.Bookmark
import app.seb3thehacker.gearslip.browser.BrowserSettings
import app.seb3thehacker.gearslip.browser.SiteIcons
import app.seb3thehacker.gearslip.car.theme.GsIconBox
import app.seb3thehacker.gearslip.car.theme.GsTone
import app.seb3thehacker.gearslip.car.theme.gsColors
import app.seb3thehacker.gearslip.car.theme.gsShape

/** How wide a tile is, icon and label together; the grid fits as many as the screen allows. */
private val TILE_WIDTH = 112.dp
private val ICON_SIZE = 64.dp
private val SEARCH_MAX_WIDTH = 560.dp
/** Five tiles across at most, so a short list sits in the middle instead of along the left. */
private val GRID_MAX_WIDTH = TILE_WIDTH * 5
/** Room above each tile for the remove button, which sits on the icon's corner. */
private val REMOVE_ROOM = 10.dp

/**
 * What the browser shows before a page is open: a search bar, and the bookmarks as big tiles.
 * Holding a tile shows a remove button on every tile, as a launcher does; tapping anywhere
 * else, or Done, puts them away. Adding happens from the browser's star, or on the phone.
 */
@Composable
internal fun BrowserStartPage(onOpen: (String) -> Unit, onSearch: () -> Unit, onAdd: () -> Unit) {
    val bookmarks by BrowserSettings.bookmarks.collectAsState()
    var editing by remember { mutableStateOf(false) }
    if (bookmarks.isEmpty()) editing = false

    // A narrow, centred column: on a wide car screen a bar from edge to edge reads as a toolbar
    // rather than a search box, and tiles spread across it look scattered.
    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        SearchBar(onSearch)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(TILE_WIDTH),
            contentPadding = PaddingValues(bottom = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.widthIn(max = GRID_MAX_WIDTH).fillMaxSize(),
        ) {
            items(bookmarks, key = { it.url }) { bookmark ->
                BookmarkTile(
                    bookmark,
                    editing = editing,
                    onClick = { if (editing) editing = false else onOpen(bookmark.url) },
                    onHold = { editing = true },
                    onRemove = { BrowserSettings.removeBookmark(bookmark.url) },
                )
            }
            item(key = "add") {
                Box(Modifier.padding(top = REMOVE_ROOM)) {
                if (editing) {
                    Tile(stringResource(R.string.web_done), onClick = { editing = false }) {
                        Icon(Icons.Filled.Close, null, Modifier.size(28.dp))
                    }
                } else {
                    Tile(stringResource(R.string.web_add_bookmark), onClick = onAdd) {
                        Icon(Icons.Filled.Add, null, Modifier.size(28.dp))
                    }
                }
                }
            }
        }
    }
}

/** Looks like a text field, but opens the car keyboard: the same as the header's Search. */
@Composable
private fun SearchBar(onSearch: () -> Unit) {
    val colors = gsColors(GsTone.Neutral)
    GsIconBox(onSearch, Modifier.widthIn(max = SEARCH_MAX_WIDTH).fillMaxWidth().height(56.dp), colors, shape = CircleShape) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Search, null, Modifier.size(24.dp))
            Text(
                stringResource(R.string.web_search),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

@Composable
private fun BookmarkTile(bookmark: Bookmark, editing: Boolean, onClick: () -> Unit, onHold: () -> Unit, onRemove: () -> Unit) {
    val context = LocalContext.current
    val host = bookmark.host
    val version by SiteIcons.version.collectAsState()
    LaunchedEffect(host) { SiteIcons.fetch(context, host) }
    val icon = remember(host, version) { SiteIcons.icon(context, host) }
    // The remove button overhangs the icon's corner, into the room left above it, so nothing
    // clips it: neither the grid's edge nor the next row.
    Box(Modifier.width(TILE_WIDTH).padding(top = REMOVE_ROOM)) {
        Tile(bookmark.title, onClick = onClick, onHold = onHold) {
            if (icon != null) {
                Image(icon.asImageBitmap(), null, Modifier.size(40.dp).clip(gsShape()))
            } else {
                Text(
                    bookmark.title.firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        if (editing) {
            GsIconBox(
                onRemove,
                Modifier.align(Alignment.TopCenter).offset(x = ICON_SIZE / 2, y = -REMOVE_ROOM).size(28.dp),
                gsColors(GsTone.Danger),
                shape = CircleShape,
            ) {
                Icon(Icons.Filled.Close, stringResource(R.string.web_remove_bookmark, bookmark.title), Modifier.size(18.dp))
            }
        }
    }
}

/** An icon box with its label under it, the start page's one shape. */
@Composable
private fun Tile(label: String, onClick: () -> Unit, onHold: (() -> Unit)? = null, icon: @Composable () -> Unit) {
    Column(Modifier.width(TILE_WIDTH), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        GsIconBox(onClick, Modifier.size(ICON_SIZE), gsColors(GsTone.Tonal), onLongClick = onHold) { icon() }
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        )
    }
}
