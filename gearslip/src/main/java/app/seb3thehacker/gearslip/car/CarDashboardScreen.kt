package app.seb3thehacker.gearslip.car

import androidx.compose.ui.res.stringResource
import app.seb3thehacker.gearslip.R
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.location.LocationManager
import android.provider.CalendarContract
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import app.seb3thehacker.gearslip.car.theme.GsColors
import app.seb3thehacker.gearslip.car.theme.GsIconBox
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.seb3thehacker.gearslip.GearslipLog
import java.net.HttpURLConnection
import java.net.URL
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Opened from the nav bar clock: the current weather, the week's calendar and the phone's
 * notifications, at a glance. The first two
 * need permissions granted on the phone (READ_CALENDAR, location) - if either was declined
 * this just shows an empty state instead of asking again from inside the car UI.
 */
@Composable
fun CarDashboardScreen() {
    val context = LocalContext.current.applicationContext

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        ScreenHeader(stringResource(R.string.dash_today))
        WeatherCard(context)
        AgendaCard(context)
        NotificationsSection()
    }
}

@Composable
private fun WeatherCard(context: Context) {
    val state by Weather.state.collectAsState()
    val navigator = LocalCarNavigator.current
    LaunchedEffect(Unit) { Weather.refresh(context) }

    GsIconBox(
        onClick = { navigator.weather() },
        colors = GsColors(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurface),
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (val s = state) {
                WeatherState.Loading -> Text(stringResource(R.string.dash_getting_weather), style = MaterialTheme.typography.bodyLarge)
                is WeatherState.Unavailable -> Text(
                    stringResource(s.reason), style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                is WeatherState.Ready -> {
                    val w = s.data
                    WeatherIcon(w.code, w.isDay, size = 44.dp)
                    Column(Modifier.weight(1f)) {
                        Text(
                            "${w.temp}${w.tempUnit}  ·  ${Weather.condition(w.code)}",
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        Text(w.place, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    NextHours(w.hours, Modifier.weight(1.3f))
                }
            }
        }
    }
}

/**
 * The next few hours beside the weather now, in the same card, so a glance answers "will it rain
 * on the way" without opening Weather. As many as fit, up to five; none if fewer than three would.
 */
@Composable
private fun NextHours(all: List<HourForecast>, modifier: Modifier) {
    val now = System.currentTimeMillis()
    // The forecast is cached, so its first hours may already be past.
    val hours = all.filter { it.time > now }
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        val fit = (maxWidth / HOUR_WIDTH).toInt().coerceAtMost(5)
        if (fit < 3 || hours.size < 3) return@BoxWithConstraints
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(1.dp).height(64.dp).background(MaterialTheme.colorScheme.outlineVariant))
            hours.take(fit).forEach { h ->
                Column(
                    Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(h.time)).replace(":00", ""),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    WeatherIcon(h.code, h.isDay, size = 24.dp)
                    Text("${h.temp}°", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    // Kept as a blank line when dry, so every column lines up.
                    Text(
                        if (h.rainChance >= RAIN_SHOWN) "${h.rainChance}%" else " ",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

private val HOUR_WIDTH = 64.dp

/** Below this a chance of rain is noise on a glance card; the Weather screen still shows it. */
private const val RAIN_SHOWN = 20

@Composable
private fun AgendaCard(context: Context) {
    val events by produceState<List<AgendaEvent>?>(cachedAgenda) {
        value = withContext(Dispatchers.IO) { fetchAgenda(context) }.also { cachedAgenda = it }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.dash_coming_up), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        val list = events
        when {
            list == null -> Text(stringResource(R.string.dash_loading_calendar), style = MaterialTheme.typography.bodyMedium)
            list.isEmpty() -> Text(
                stringResource(R.string.dash_nothing_week),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> list.forEach { EventRow(it) }
        }
    }
}

@Composable
private fun EventRow(event: AgendaEvent) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                eventWhen(event, stringResource(R.string.dash_all_day)),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                event.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** stringResource(R.string.dash_today) events show just a time; later days get a weekday so a week's list stays readable. */
private fun eventWhen(event: AgendaEvent, allDay: String): String {
    val zone = if (event.allDay) TimeZone.getTimeZone("UTC") else TimeZone.getDefault()
    val start = Calendar.getInstance(zone).apply { timeInMillis = event.startMillis }
    val today = Calendar.getInstance()
    val sameDay = start.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
        start.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
    val time = if (event.allDay) allDay else DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(event.startMillis))
    if (sameDay || (event.allDay && start.before(today))) return time
    return SimpleDateFormat("EEE", Locale.getDefault()).apply { timeZone = zone }.format(Date(event.startMillis)) + " " + time
}

private data class AgendaEvent(val title: String, val startMillis: Long, val allDay: Boolean)

@Volatile private var cachedAgenda: List<AgendaEvent>? = null

/** Loads the weather and the agenda ahead of time, so the dashboard opens with both in place. */
fun warmDashboard(context: Context) {
    Weather.refresh(context, force = true)
    cachedAgenda = fetchAgenda(context)
}

/**
 * Empty list means no permission or nothing scheduled. Looks from the start of today so an
 * event already under way still shows, a week ahead so tomorrow's does too, and skips ones
 * that have finished.
 */
private fun fetchAgenda(context: Context): List<AgendaEvent> {
    if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
        return emptyList()
    }

    val now = System.currentTimeMillis()
    val startOfToday = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val end = startOfToday + 7 * 24 * 60 * 60 * 1000L
    val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
        .appendPath(startOfToday.toString())
        .appendPath(end.toString())
        .build()

    val events = mutableListOf<AgendaEvent>()
    val projection = arrayOf(
        CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
        CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY,
    )
    runCatching {
        context.contentResolver.query(uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")
            ?.use { cursor: Cursor ->
                while (cursor.moveToNext() && events.size < 10) {
                    val allDay = cursor.getInt(3) == 1
                    // All-day events are stored in UTC midnight, so "ended" means the day is over, not the instant.
                    if (!allDay && cursor.getLong(2) < now) continue
                    events.add(AgendaEvent(cursor.getString(0) ?: "(untitled)", cursor.getLong(1), allDay))
                }
            }
    }.onFailure { GearslipLog.w("calendar query failed: ${it.message}") }
    return events
}
