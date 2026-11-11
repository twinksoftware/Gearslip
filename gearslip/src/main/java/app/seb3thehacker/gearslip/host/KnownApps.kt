package app.seb3thehacker.gearslip.host

import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest

/**
 * Apps that have been run through Gearslip from start to finish and behave. The launcher marks
 * them so a driver can pick something that works without finding out at the wheel.
 *
 * An app belongs here only after a real session: the app starts, draws, takes touches and
 * answers its buttons. Apps that connect but show no library, or refuse Gearslip as a host, stay
 * off the list.
 */
object KnownApps {

    private val working = setOf(
        "app.organicmaps",       // Organic Maps: map, search, routing, settings
        "net.osmand.plus",       // OsmAnd: map, search, routing, settings
        "in.krosbits.musicolet", // Musicolet: browse, play, seek, lyrics
        "com.kododake.aabrowser", // Aardvark: browser template, starts, draws, takes touches
        "com.vivi.vivimusic", // Vivi Music: browse, play, seek, works well
        "com.metrolist.music", // Metrolist: browse, play, works
        "nl.flitsmeister", // Flitsmeister: map, routing, alerts - works well
        "com.mapquest.android.ace", // MapQuest: map, routing, navigation
        "com.mapswithme.maps.pro", // MAPS.ME: map, search, place pages, navigation
        "app.vela", // Vela Maps: map, search, route options, turn-by-turn with lanes
    )

    fun works(packageName: String): Boolean = packageName in working

    /**
     * Apps whose media player works through Gearslip's own player even though their car screen
     * doesn't. Only the player tile gets the check, not the "· Browse" one.
     */
    private val workingPlayer = setOf(
        "com.spotify.music", // Spotify: play, browse, seek through Gearslip's player
    )

    fun playerWorks(packageName: String): Boolean = packageName in workingPlayer

    /**
     * Apps whose car screen refuses Gearslip but whose player works. When the player covers the
     * app (see [playerWorks]) the launcher drops the dead "· Browse" tile entirely rather than
     * show it; otherwise that tile gets a red X. Car library 1.9 and later accepts only Google's
     * host on a phone.
     */
    private val brokenScreen = setOf(
        "com.spotify.music", // Spotify 9.1.86 and later: car library 1.9 rejects Gearslip
    )

    fun screenBroken(packageName: String): Boolean = packageName in brokenScreen

    /**
     * Google's own car apps, which never give Gearslip a usable screen. They used to be left off
     * the launcher, which looked like a bug to anyone who had them; now they're listed like any
     * other broken app, starting unticked (see [startsHidden]).
     */
    private val google = setOf(
        "com.google.android.dialer",               // Google Phone
        "com.google.android.googlequicksearchbox",  // Google Assistant driving surfaces
        "com.google.android.apps.maps",             // Google Maps: shows as a messenger, and its car UI is Android Auto's own
    )

    /** Shown on the launcher with a red X, and unticked to start with. */
    private val broken = google + setOf(
        "com.google.android.apps.messaging", // Google Messages
        "com.waze",                          // Waze: the official app refuses Gearslip; the Morphe-patched one works (see patched())
        "com.generalmagic.magicearth",       // Magic Earth: never gets past connecting
    )

    fun isBroken(context: Context, packageName: String): Boolean =
        packageName in broken && !patched(context, packageName)

    /**
     * Whether a tile starts unticked in the phone's list of apps to show, so the drawer opens
     * with only what works. Gearslip's own Messages screen works for any messenger, so a broken
     * app's messaging tile still shows, except Google Maps', which has no messages worth reading.
     */
    fun startsHidden(context: Context, packageName: String, messaging: Boolean): Boolean =
        isBroken(context, packageName) && (!messaging || packageName in google)

    /** Google's own apps get the red X even as a messenger: Google Maps shows only as one. */
    fun brokenAsMessenger(context: Context, packageName: String): Boolean =
        packageName in google && isBroken(context, packageName)

    /**
     * The certificate each broken app's own publisher signs it with. A copy signed with another
     * key has been patched to accept Gearslip, like Waze with the waze-gearslip Morphe patch, and
     * works.
     */
    private val officialSigners = mapOf(
        "com.waze" to "03637f6c5d8f604e6fdb79a6ffbfa578de4e318f8da22fc6106665247f8807d7",
    )

    /** Whether the installed copy is signed by someone other than its publisher. */
    private fun patched(context: Context, packageName: String): Boolean {
        val official = officialSigners[packageName] ?: return false
        val signers = runCatching {
            context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners
        }.getOrNull() ?: return false
        val sha256 = MessageDigest.getInstance("SHA-256")
        return signers.isNotEmpty() && signers.none { cert ->
            sha256.digest(cert.toByteArray()).joinToString("") { "%02x".format(it) } == official
        }
    }

    /**
     * Shown on the launcher with a yellow mark: starts and can be used, but not cleanly enough to
     * call working. Wins over [works] and [playerWorks] for the badge.
     */
    private val partial = setOf(
        "com.spotify.music",        // Spotify: plays through Gearslip's player, but the app has to be opened on the phone first
        "deezer.android.app",       // Deezer: the same - it has to be opened on the phone first
    )

    fun isPartial(packageName: String): Boolean = packageName in partial
}
