package app.seb3thehacker.gearslip.stats

import android.app.job.JobScheduler
import android.content.Context
import android.os.Build
import app.seb3thehacker.gearslip.BuildConfig
import app.seb3thehacker.gearslip.GearslipLog
import app.seb3thehacker.gearslip.ServiceDiscovery
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Opt-in usage counts: a short note to the stats Worker (stats-worker/ in the repo). Off until the
 * driver turns it on in setup or Settings. Only the app version is always sent; everything else
 * is one of the driver's [Choices]. A random install ID counts a phone once; nothing ties it to a
 * person, and the Worker keeps no IP addresses.
 *
 * Notes go out only while Gearslip is in use: the first time the app is opened each day, and
 * after every car session. Nothing runs in the background, so a phone that stops using Gearslip
 * goes quiet. A note that fails waits for the next one. With no [BuildConfig.STATS_URL] set at
 * build time, nothing is sent.
 */
object UsageStats {

    /** What the driver agreed to share beyond the app version. */
    data class Choices(
        val android: Boolean = true,
        val phone: Boolean = true,
        val cars: Boolean = true,
        /** How each car session went: how far it got, how and when it ended. Off until ticked. */
        val failures: Boolean = false,
    )

    private const val PREFS = "usage_stats"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_ASKED = "asked"
    private const val KEY_ID = "install_id"
    /** The phone details the server last got, and when, so they go again only on a change. */
    private const val KEY_SENT_DETAILS = "sent_details"
    private const val KEY_DETAILS_DAY = "details_day"
    private const val DETAILS_REFRESH_DAYS = 30L
    private const val KEY_CARS = "cars"
    /** The cars the last note carried, so Settings can show them after the queue empties. */
    private const val KEY_LAST_CARS = "last_cars"
    private const val KEY_SENT_DAY = "sent_day"
    /** The daily background job of earlier builds, cancelled wherever it's still scheduled. */
    private const val OLD_JOB_ID = 4_207
    private const val MAX_CARS = 10
    /** Each session for the "Where connections fail" share, waiting for the next note. */
    private const val KEY_SESSIONS = "sessions"
    private const val KEY_LAST_SESSIONS = "last_sessions"
    private const val MAX_SESSIONS = 20

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Debug builds send too, marked "debug". The server keeps those notes apart, so testing never
     * muddies the numbers.
     */
    val sends: Boolean get() = BuildConfig.STATS_URL.isNotBlank()

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    /** Whether the driver has answered the question in setup, either way. */
    fun asked(context: Context): Boolean = prefs(context).getBoolean(KEY_ASKED, false)

    fun choices(context: Context): Choices {
        val p = prefs(context)
        return Choices(
            android = p.getBoolean("share_android", true),
            phone = p.getBoolean("share_phone", true),
            cars = p.getBoolean("share_cars", true),
            failures = p.getBoolean("share_failures", false),
        )
    }

    fun set(context: Context, enabled: Boolean, choices: Choices = choices(context)) {
        val wasEnabled = enabled(context)
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putBoolean(KEY_ASKED, true)
            .putBoolean("share_android", choices.android)
            .putBoolean("share_phone", choices.phone)
            .putBoolean("share_cars", choices.cars)
            .putBoolean("share_failures", choices.failures)
            // The server drops details from a box once it's unticked, so the next note sends
            // them again rather than assuming the server still has them.
            .remove(KEY_SENT_DETAILS)
            .apply()
        // Turning it on sends nothing yet: the driver may still be ticking boxes. The first note
        // waits for [choicesClosed].
        if (!enabled) prefs(context).edit().remove(KEY_CARS).remove(KEY_LAST_CARS).remove(KEY_SENT_DAY)
            .remove(KEY_SESSIONS).remove(KEY_LAST_SESSIONS).apply()
        if (!choices.failures) prefs(context).edit().remove(KEY_SESSIONS).remove(KEY_LAST_SESSIONS).apply()
        // Turned on anywhere - Settings, Home, or the ask itself - answers the one-time ask.
        if (enabled && !wasEnabled) WorkedPrompt.onSharingOn(context)
    }

    /** The driver left the screen with the usage note choices: send today's note if it's due. */
    fun choicesClosed(context: Context) {
        if (prefs(context).getString(KEY_SENT_DAY, null) != LocalDate.now().toString()) sendSoon(context)
    }

    /**
     * Gearslip was opened: send today's note unless one already went out, or sooner if a session
     * is still waiting, like one that ended in a crash before its note could go.
     */
    fun onAppOpened(context: Context) {
        context.getSystemService(JobScheduler::class.java)?.cancel(OLD_JOB_ID)
        val p = prefs(context)
        val waiting = p.getString(KEY_CARS, "[]") != "[]" || p.getString(KEY_SESSIONS, "[]") != "[]"
        if (waiting || p.getString(KEY_SENT_DAY, null) != LocalDate.now().toString()) sendSoon(context)
    }

    private val sending = AtomicBoolean(false)
    @Volatile private var sendAgain = false
    /** Guards the saved car list, which a session's end and a finished send both rewrite. */
    private val carsLock = Any()

    /**
     * Sends off the calling thread. A call while a note is in flight sends once more after it, so
     * a car recorded mid-send still goes out.
     */
    private fun sendSoon(context: Context) {
        if (!sends || !enabled(context)) return
        if (!sending.compareAndSet(false, true)) { sendAgain = true; return }
        val app = context.applicationContext
        Thread({
            try {
                do { sendAgain = false } while (send(app) && sendAgain)
            } finally {
                sending.set(false)
            }
        }, "gearslip-stats").start()
    }

    private fun installId(context: Context): String {
        val p = prefs(context)
        return p.getString(KEY_ID, null) ?: UUID.randomUUID().toString().also { p.edit().putString(KEY_ID, it).apply() }
    }

    /**
     * A car session ended. [info] is null when the car hung up before it said who it is, for
     * instance after rejecting the certificate; the protocol version and [outcome] still say a lot.
     */
    fun recordSession(
        context: Context,
        info: ServiceDiscovery.HeadUnitInfo?,
        protocol: String,
        outcome: String,
        screen: String = "",
        dpi: Int = 0,
        session: JSONObject? = null,
        link: String = "",
        audio: String = "",
    ) {
        if (!enabled(context)) return
        val p = prefs(context)
        synchronized(carsLock) {
            addCar(p, info, protocol, outcome, screen, dpi, link, audio)
            if (session != null && choices(context).failures) addSession(p, info, protocol, session, link, audio)
        }
        sendSoon(context)
    }

    private fun addSession(
        p: android.content.SharedPreferences,
        info: ServiceDiscovery.HeadUnitInfo?,
        protocol: String,
        session: JSONObject,
        link: String,
        audio: String,
    ) {
        val sessions = runCatching { JSONArray(p.getString(KEY_SESSIONS, "[]")) }.getOrDefault(JSONArray())
        if (sessions.length() >= MAX_SESSIONS) return
        // The same car fields as the cars list, so the dashboard can line the two up.
        session.put("name", info?.headUnitName.orEmpty())
            .put("car", info?.carModel.orEmpty())
            .put("year", info?.carYear.orEmpty())
            .put("protocol", protocol)
        if (link.isNotEmpty()) session.put("link", link).put("audio", audio)
        sessions.put(session)
        // Written at once: after a crash the app is about to die, and a deferred write would be lost.
        p.edit().putString(KEY_SESSIONS, sessions.toString()).commit()
    }

    private fun addCar(
        p: android.content.SharedPreferences,
        info: ServiceDiscovery.HeadUnitInfo?,
        protocol: String,
        outcome: String,
        screen: String,
        dpi: Int,
        link: String,
        audio: String,
    ) {
        val cars = runCatching { JSONArray(p.getString(KEY_CARS, "[]")) }.getOrDefault(JSONArray())
        val car = JSONObject()
            .put("name", info?.headUnitName.orEmpty())
            .put("car", info?.carModel.orEmpty())
            .put("year", info?.carYear.orEmpty())
            .put("make", info?.headUnitMake.orEmpty())
            .put("model", info?.headUnitModel.orEmpty())
            .put("protocol", protocol)
            .put("result", outcome)
        if (screen.isNotEmpty()) car.put("screen", screen).put("dpi", dpi)
        // USB or wireless, and whether music went over USB or Bluetooth: settings, not the person.
        if (link.isNotEmpty()) car.put("link", link).put("audio", audio)
        // The same car and outcome twice in a day is one fact, not two.
        val seen = (0 until cars.length()).any { cars.optJSONObject(it)?.toString() == car.toString() }
        if (!seen && cars.length() < MAX_CARS) cars.put(car)
        p.edit().putString(KEY_CARS, cars.toString()).commit()
    }

    /**
     * Which Android this is: a privacy OS by name, or "Stock" with the phone's brand.
     *
     * GrapheneOS reports the same build fields as stock Pixel firmware, so its own setup app gives
     * it away; /e/OS is told apart from the LineageOS it's built on by its app store. The rest set
     * build properties of their own. The iodéOS and DivestOS checks are best guesses from their
     * build names, not confirmed on a real phone.
     */
    fun osName(context: Context): String {
        fun installed(pkg: String) = installed(context, pkg)
        val names = listOf("ro.build.flavor", "ro.build.display.id", "ro.lineage.version")
            .joinToString(" ") { prop(it) } + " " + Build.DISPLAY + " " + Build.FINGERPRINT
        return when {
            isGrapheneOs(context) -> "GrapheneOS"
            prop("ro.calyxos.version").isNotEmpty() -> "CalyxOS"
            installed("foundation.e.apps") -> "/e/OS"
            names.contains("iode", ignoreCase = true) -> "iodéOS"
            names.contains("divest", ignoreCase = true) -> "DivestOS"
            prop("ro.lineage.version").isNotEmpty() -> "LineageOS"
            else -> "Stock (${Build.BRAND.replaceFirstChar { it.uppercase() }})"
        }
    }

    /** GrapheneOS, told apart from stock Pixel firmware by its own setup app. */
    fun isGrapheneOs(context: Context) = installed(context, "app.grapheneos.setupwizard")

    private fun installed(context: Context, pkg: String) =
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    /** A system property, or "" when it isn't set or can't be read. */
    private fun prop(name: String): String = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, name) as String
    }.getOrDefault("")

    private fun details(context: Context, c: Choices) = JSONObject().apply {
        if (c.android) put("android", Build.VERSION.RELEASE).put("os", osName(context))
        if (c.phone) put("phone", "${Build.MANUFACTURER} ${Build.MODEL}")
    }

    private var signerCache: String? = null

    /** The first 16 hex digits of the SHA-256 of this APK's signing certificate. */
    private fun signer(context: Context): String = signerCache ?: runCatching {
        val cert = context.packageManager
            .getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
            .signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray() ?: return@runCatching ""
        java.security.MessageDigest.getInstance("SHA-256").digest(cert)
            .take(8).joinToString("") { "%02x".format(it) }
    }.getOrDefault("").also { signerCache = it }

    /**
     * What a note carries. With [everything], every field the driver shares, as Settings shows it;
     * otherwise exactly what the next note would send, which leaves out unchanged phone details.
     */
    fun preview(context: Context, everything: Boolean = false): JSONObject {
        val c = choices(context)
        val p = prefs(context)
        val note = JSONObject()
            .put("id", installId(context))
            .put("version", BuildConfig.VERSION_NAME)
            .put("code", BuildConfig.VERSION_CODE)
            .put("build", if (BuildConfig.DEBUG) "debug" else "release")
            // Who signed this copy, so a fork's builds can be told from Gearslip's own. Every copy
            // from the same publisher sends the same value, so it says nothing about the phone.
            .put("signer", signer(context))
            // Which boxes are ticked, so the server knows what this phone shares and can drop
            // anything it kept from a box since unticked.
            .put("shares", JSONArray().apply {
                if (c.android) put("android")
                if (c.phone) put("phone")
                if (c.cars) put("cars")
                if (c.failures) put("sessions")
            })
        // Phone details change rarely, so they ride along only when they differ from what the
        // server last got, or once a month in case it lost them. Unticked ones are left out, and
        // the server keeps the last value it had.
        val details = details(context, c)
        val stale = p.getString(KEY_DETAILS_DAY, null)?.let { LocalDate.parse(it).plusDays(DETAILS_REFRESH_DAYS) <= LocalDate.now() } ?: true
        if (details.length() > 0 && (everything || stale || details.toString() != p.getString(KEY_SENT_DETAILS, null))) {
            details.keys().forEach { note.put(it, details.get(it)) }
        }
        if (c.cars) {
            var cars = runCatching { JSONArray(p.getString(KEY_CARS, "[]")) }.getOrDefault(JSONArray())
            // A car joins the queue when a drive ends and goes out straight away, so the queue
            // is nearly always empty. Settings shows the last cars sent instead of a bare [].
            if (everything && cars.length() == 0) {
                cars = runCatching { JSONArray(p.getString(KEY_LAST_CARS, "[]")) }.getOrDefault(JSONArray())
            }
            note.put("cars", cars)
        }
        if (c.failures) {
            var sessions = runCatching { JSONArray(p.getString(KEY_SESSIONS, "[]")) }.getOrDefault(JSONArray())
            if (everything && sessions.length() == 0) {
                sessions = runCatching { JSONArray(p.getString(KEY_LAST_SESSIONS, "[]")) }.getOrDefault(JSONArray())
            }
            note.put("sessions", sessions)
        }
        return note
    }

    /**
     * A made-up session, for "What gets sent" to show before the first real one: a 25-minute
     * drive that ended with the car switched off.
     */
    val exampleSession: JSONObject
        get() = JSONObject()
            .put("stage", "streaming")
            .put("ended", "byebye")
            .put("code", "bye 1")
            .put("failed_at", 1500)
            .put("seconds", 1500)
            .put("cert", "downloaded")
            .put("name", "Head unit name")
            .put("car", "Car model")
            .put("year", "2021")
            .put("protocol", "1.5")
            .put("link", "usb")
            .put("audio", "bluetooth")

    /** Sends the note now; false when it didn't get through. */
    private fun send(context: Context): Boolean {
        if (!sends || !enabled(context)) return true
        val endpoint = BuildConfig.STATS_URL
        val p = prefs(context)
        val note = synchronized(carsLock) { preview(context) }
        val sentCars = note.optJSONArray("cars")?.toString()
        val sentSessions = note.optJSONArray("sessions")?.toString()
        val sentDetails = if (note.has("android") || note.has("phone")) details(context, choices(context)).toString() else null
        val body = note.toString().toByteArray()
        return runCatching {
            val conn = (URL("$endpoint/ping").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("Content-Type", "application/json")
            }
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            conn.disconnect()
            if (code in 200..299) {
                // Sent: the cars start over, unless a session ended meanwhile and added one, which
                // stays for the next note.
                synchronized(carsLock) {
                    val edit = p.edit().putString(KEY_SENT_DAY, LocalDate.now().toString())
                    if (sentDetails != null) edit.putString(KEY_SENT_DETAILS, sentDetails).putString(KEY_DETAILS_DAY, LocalDate.now().toString())
                    if (sentCars != null && sentCars != "[]") edit.putString(KEY_LAST_CARS, sentCars)
                    if (sentCars == null || p.getString(KEY_CARS, "[]") == sentCars) edit.remove(KEY_CARS)
                    if (sentSessions != null && sentSessions != "[]") edit.putString(KEY_LAST_SESSIONS, sentSessions)
                    if (sentSessions == null || p.getString(KEY_SESSIONS, "[]") == sentSessions) {
                        edit.remove(KEY_SESSIONS)
                    } else if (sentSessions != "[]") {
                        // A session ended mid-send: keep only the ones this note didn't carry.
                        val now = JSONArray(p.getString(KEY_SESSIONS, "[]"))
                        val sent = JSONArray(sentSessions).length()
                        val left = JSONArray().also { for (i in sent until now.length()) it.put(now.get(i)) }
                        edit.putString(KEY_SESSIONS, left.toString())
                    }
                    edit.apply()
                }
                GearslipLog.i("stats: note sent")
                true
            } else {
                GearslipLog.w("stats: the server answered $code")
                false
            }
        }.getOrElse {
            GearslipLog.w("stats: could not send: ${it.message}")
            false
        }
    }
}
