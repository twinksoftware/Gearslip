package app.seb3thehacker.gearslip.car

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import app.seb3thehacker.gearslip.R
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import app.seb3thehacker.gearslip.GearslipLog
import app.seb3thehacker.gearslip.SessionStatus
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

class HourForecast(val time: Long, val temp: Int, val code: Int, val isDay: Boolean, val rainChance: Int)

class DayForecast(val date: Long, val high: Int, val low: Int, val code: Int, val rainChance: Int)

class WeatherData(
    val place: String,
    val temp: Int,
    val feelsLike: Int,
    val code: Int,
    val isDay: Boolean,
    val humidity: Int,
    val wind: Int,
    val tempUnit: String,
    val windUnit: String,
    val hours: List<HourForecast>,
    val days: List<DayForecast>,
    val fetchedAt: Long,
)

sealed interface WeatherState {
    data object Loading : WeatherState
    data class Unavailable(@param:StringRes val reason: Int) : WeatherState
    data class Ready(val data: WeatherData) : WeatherState
}

/**
 * Current weather and a week's forecast for wherever the phone is, from Open-Meteo (free, no
 * key or account; the data is CC BY 4.0, so the weather screen credits it).
 *
 * The location is the phone's last fix if it is recent, otherwise a fresh one is requested, so
 * a phone that has been idle does not leave the card on "unavailable".
 */
object Weather {

    private const val TTL_MS = 30 * 60 * 1000L
    private const val FIX_MAX_AGE_MS = 30 * 60 * 1000L
    private const val FIX_TIMEOUT_S = 12L

    private val flow = MutableStateFlow<WeatherState>(WeatherState.Loading)
    val state: StateFlow<WeatherState> = flow

    private val io = Executors.newSingleThreadExecutor { Thread(it, "gearslip-weather").apply { isDaemon = true } }

    private var placeCache: Pair<String, String>? = null // rounded "lat,lon" to place name

    /** Set while the car preview is on the phone's screen, where the dashboard shows the weather too. */
    @Volatile var previewOpen = false

    /**
     * Location is read only while a car is connected or the preview is open. Opening Gearslip on
     * the phone used to warm the forecast too, which read the location with no car anywhere.
     */
    private val mayLocate: Boolean
        get() = previewOpen || SessionStatus.state.value.phase.let {
            it == SessionStatus.Phase.CONNECTING || it == SessionStatus.Phase.PROJECTING
        }

    /** Runs off the main thread. Fresh data younger than [TTL_MS] is kept unless [force]. */
    fun refresh(context: Context, force: Boolean = false) {
        if (!mayLocate) return
        val current = flow.value
        if (!force && current is WeatherState.Ready && System.currentTimeMillis() - current.data.fetchedAt < TTL_MS) return
        val app = context.applicationContext
        io.execute { load(app) }
    }

    @Synchronized
    private fun load(context: Context) {
        // Keep showing the old reading while a new one loads; only the first load shows a spinner.
        val location = locate(context)
        if (location == null) {
            if (flow.value !is WeatherState.Ready) {
                val allowed = hasLocationPermission(context)
                flow.value = WeatherState.Unavailable(
                    if (allowed) R.string.weather_waiting_fix else R.string.weather_allow_location,
                )
            }
            return
        }
        val data = runCatching { fetch(context, location) }
            // Only the kind of error: some messages carry the request URL, and that holds the location.
            .onFailure { GearslipLog.w("weather fetch failed: ${it.javaClass.simpleName}") }
            .getOrNull()
        if (data != null) {
            flow.value = WeatherState.Ready(data)
            // No place name: logs get shared in bug reports.
            GearslipLog.i("weather: loaded")
        }
        else if (flow.value !is WeatherState.Ready) flow.value = WeatherState.Unavailable(R.string.weather_unreachable)
    }

    // --- location -------------------------------------------------------------------------

    private fun hasLocationPermission(context: Context) =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun locate(context: Context): Location? {
        if (!hasLocationPermission(context)) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val known = manager.allProviders
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        if (known != null && System.currentTimeMillis() - known.time < FIX_MAX_AGE_MS) return known

        val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { it in manager.allProviders && manager.isProviderEnabled(it) }
            ?: return known
        val latch = CountDownLatch(1)
        var fresh: Location? = null
        runCatching {
            manager.getCurrentLocation(provider, null, io) { fresh = it; latch.countDown() }
            latch.await(FIX_TIMEOUT_S, TimeUnit.SECONDS)
        }.onFailure { GearslipLog.w("weather: fresh fix failed: ${it.message}") }
        // A stale fix still gives the right city, which is all a forecast needs.
        return fresh ?: known
    }

    private fun placeName(context: Context, location: Location): String {
        val key = "%.2f,%.2f".format(Locale.US, location.latitude, location.longitude)
        placeCache?.let { if (it.first == key) return it.second }
        val name = runCatching {
            if (!Geocoder.isPresent()) return@runCatching null
            @Suppress("DEPRECATION")
            Geocoder(context, Locale.getDefault()).getFromLocation(location.latitude, location.longitude, 1)
                ?.firstOrNull()?.let { it.locality ?: it.subAdminArea ?: it.adminArea }
        }.getOrNull() ?: "%.2f, %.2f".format(Locale.US, location.latitude, location.longitude)
        placeCache = key to name
        return name
    }

    // --- network --------------------------------------------------------------------------

    private fun fetch(context: Context, location: Location): WeatherData {
        val imperial = CarSettings.imperial()
        val url = URL(
            "https://api.open-meteo.com/v1/forecast?latitude=${location.latitude}&longitude=${location.longitude}" +
                "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,is_day" +
                "&hourly=temperature_2m,weather_code,precipitation_probability,is_day" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                "&timezone=auto&timeformat=unixtime&forecast_days=7" +
                if (imperial) "&temperature_unit=fahrenheit&wind_speed_unit=mph" else "",
        )
        val body = (url.openConnection() as HttpURLConnection).run {
            connectTimeout = 6_000
            readTimeout = 8_000
            inputStream.bufferedReader().use { it.readText() }
        }
        val json = JSONObject(body)
        val cur = json.getJSONObject("current")

        val hourly = json.getJSONObject("hourly")
        val times = hourly.getJSONArray("time")
        val nowSec = System.currentTimeMillis() / 1000
        val start = (0 until times.length()).firstOrNull { times.getLong(it) >= nowSec - 3600 } ?: 0
        val hours = (start until minOf(start + 12, times.length())).map { i ->
            HourForecast(
                time = times.getLong(i) * 1000,
                temp = Math.round(hourly.getJSONArray("temperature_2m").getDouble(i)).toInt(),
                code = hourly.getJSONArray("weather_code").getInt(i),
                isDay = hourly.getJSONArray("is_day").getInt(i) == 1,
                rainChance = hourly.getJSONArray("precipitation_probability").optInt(i, 0),
            )
        }

        val daily = json.getJSONObject("daily")
        val dates = daily.getJSONArray("time")
        val days = (0 until dates.length()).map { i ->
            DayForecast(
                date = dates.getLong(i) * 1000,
                high = Math.round(daily.getJSONArray("temperature_2m_max").getDouble(i)).toInt(),
                low = Math.round(daily.getJSONArray("temperature_2m_min").getDouble(i)).toInt(),
                code = daily.getJSONArray("weather_code").getInt(i),
                rainChance = daily.getJSONArray("precipitation_probability_max").optInt(i, 0),
            )
        }

        return WeatherData(
            place = placeName(context, location),
            temp = Math.round(cur.getDouble("temperature_2m")).toInt(),
            feelsLike = Math.round(cur.getDouble("apparent_temperature")).toInt(),
            code = cur.getInt("weather_code"),
            isDay = cur.getInt("is_day") == 1,
            humidity = cur.getInt("relative_humidity_2m"),
            wind = Math.round(cur.getDouble("wind_speed_10m")).toInt(),
            tempUnit = if (imperial) "°F" else "°C",
            windUnit = if (imperial) "mph" else "km/h",
            hours = hours,
            days = days,
            fetchedAt = System.currentTimeMillis(),
        )
    }

    /** Open-Meteo's WMO weather codes, in words on the screen. [describe] is the English the assistant says. */
    @StringRes
    fun conditionRes(code: Int): Int = when (code) {
        0 -> R.string.weather_clear
        1 -> R.string.weather_mostly_clear
        2 -> R.string.weather_partly_cloudy
        3 -> R.string.weather_overcast
        45, 48 -> R.string.weather_fog
        in 51..57 -> R.string.weather_drizzle
        in 61..67 -> R.string.weather_rain
        in 71..77 -> R.string.weather_snow
        in 80..82 -> R.string.weather_rain_showers
        in 85..86 -> R.string.weather_snow_showers
        in 95..99 -> R.string.weather_thunderstorm
        else -> 0
    }

    @Composable
    fun condition(code: Int): String = conditionRes(code).let { if (it == 0) "-" else stringResource(it) }

    /** Open-Meteo's WMO weather codes, in words. */
    fun describe(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        in 51..57 -> "Drizzle"
        in 61..67 -> "Rain"
        in 71..77 -> "Snow"
        in 80..82 -> "Rain showers"
        in 85..86 -> "Snow showers"
        in 95..99 -> "Thunderstorm"
        else -> "-"
    }
}
