package app.seb3thehacker.gearslip.car

import androidx.compose.ui.res.stringResource
import app.seb3thehacker.gearslip.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The weather in full: now, the next twelve hours and the week. Opened from the nav bar or the dashboard. */
@Composable
fun WeatherScreen() {
    val context = LocalContext.current.applicationContext
    val state by Weather.state.collectAsState()
    LaunchedEffect(Unit) { Weather.refresh(context) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ScreenHeader(stringResource(R.string.crumb_weather))
        when (val s = state) {
            WeatherState.Loading -> Text(stringResource(R.string.weather_getting), style = MaterialTheme.typography.titleMedium)
            is WeatherState.Unavailable -> Text(
                stringResource(s.reason), style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is WeatherState.Ready -> Forecast(s.data)
        }
    }
}

@Composable
private fun Forecast(w: WeatherData) {
    Text(w.place, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.extraLarge) {
        Row(
            Modifier.fillMaxWidth().padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            WeatherIcon(w.code, w.isDay, size = 88.dp, animate = true)
            Column {
                Text("${w.temp}${w.tempUnit}", fontSize = 56.sp, fontWeight = FontWeight.Bold)
                Text(Weather.condition(w.code), style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.weather_feels_like, "${w.feelsLike}${w.tempUnit}"), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.weather_humidity, w.humidity), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.weather_wind, "${w.wind} ${w.windUnit}"), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }

    Text(stringResource(R.string.weather_next_hours), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(w.hours) { h ->
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
                Column(
                    Modifier.width(84.dp).padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(h.time)).replace(":00", ""),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    WeatherIcon(h.code, h.isDay, size = 32.dp)
                    Text("${h.temp}°", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (h.rainChance > 0) "${h.rainChance}%" else " ",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }

    Text(stringResource(R.string.weather_this_week), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        w.days.forEachIndexed { i, d ->
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        if (i == 0) stringResource(R.string.weather_today) else SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(d.date)),
                        Modifier.width(120.dp), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                    )
                    WeatherIcon(d.code, true, size = 28.dp)
                    Text(Weather.condition(d.code), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    if (d.rainChance > 0) Text("${d.rainChance}%", color = MaterialTheme.colorScheme.primary)
                    Text("${d.low}° / ${d.high}°", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
    Text(
        stringResource(R.string.weather_credit),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
