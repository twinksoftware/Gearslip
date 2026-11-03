package app.seb3thehacker.gearslip.car

import android.content.Context
import app.seb3thehacker.gearslip.media.CarMedia
import app.seb3thehacker.gearslip.speech.VoiceFocus
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Turns what [CarAssistant] heard into an action plus something to say back.
 *
 * Deliberately small and keyword-matched rather than run through any NLU or bundled model: a
 * fixed set of things a driver actually wants hands-free (media, calling, opening an app,
 * getting back Home), covering the same ground the rest of the car UI already does rather than
 * trying to be a general assistant.
 */
object AssistantCommands {

    suspend fun handle(context: Context, navigator: CarNavigator, frame: CarEnvironment.Frame, heardRaw: String): String {
        val heard = natural(heardRaw)
        VoiceHelp.hide()
        if (heard in HELP_WORDS || heard.startsWith("what can i say") || heard.startsWith("what can you do")) {
            VoiceHelp.show()
            return "Here's what you can say."
        }
        if (heard.matches(Regex("(fuck|f\\*+) (you|off)( too)?"))) return "Fuck you too."

        navigateCommand(context, navigator, frame, heard)?.let { return it }
        playSearchCommand(context, navigator, heard)?.let { return it }
        mediaCommand(heard)?.let { return it }
        navCommand(navigator, heard)?.let { return it }
        infoCommand(heard)?.let { return it }
        callCommand(context, heard)?.let { return it }
        openAppCommand(context, navigator, frame, heard)?.let { return it }

        return "I don't know how to do that yet."
    }

    /**
     * What was said, minus the padding people wrap a command in: "hey", "can you", "I want to",
     * "please", "thanks". Each command below then only has to know its own words.
     */
    private fun natural(raw: String): String {
        var t = raw.lowercase().replace(Regex("[.,!?]"), " ").replace(Regex("\\s+"), " ").trim()
        var changed = true
        while (changed) {
            changed = false
            for (lead in LEAD_FILLERS) if (t.startsWith("$lead ")) { t = t.removePrefix("$lead ").trim(); changed = true }
            for (tail in TAIL_FILLERS) if (t.endsWith(" $tail")) { t = t.removeSuffix(" $tail").trim(); changed = true }
        }
        return t
    }

    /** The part after the first of [triggers] that [text] starts with, or null. */
    private fun after(text: String, triggers: List<String>): String? =
        triggers.firstOrNull { text.startsWith(it) }?.let { text.removePrefix(it).trim() }

    private suspend fun allEntries(context: Context): List<Entry> {
        val vehicleData = CarSettings.experimentalFeaturesEnabled.value && CarSettings.carSensorsEnabled.value
        val builtIn = BuiltInApps.entries(LauncherMode.active.value, LauncherMode.canCall(context)).filter { it.builtIn !== BuiltInApps.VehicleData || vehicleData }
        return builtIn + (LauncherCache.entries ?: withContext(Dispatchers.IO) { LauncherCache.load(context) })
    }

    /**
     * Best label match: exact, then a label starting with [name], then one containing it. Spaces
     * and punctuation don't count, since the recognizer may hear "MapQuest" as "map quest".
     */
    private fun <T> bestMatch(items: List<T>, name: String, label: (T) -> String): T? {
        val n = squash(name.removePrefix("the ").removePrefix("my ").removeSuffix(" app"))
        if (n.isEmpty()) return null
        items.firstOrNull { squash(label(it)) == n }?.let { return it }
        // Loose matches need a real word: "pizza in a" mustn't pick "Organic Maps" for its "a".
        if (n.length < 3) return null
        return items.firstOrNull { squash(label(it)).startsWith(n) }
            ?: items.firstOrNull { squash(label(it)).contains(n) }
    }

    private fun squash(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /**
     * "navigate to X [with|using|on|via Y]" - Y picks the nav app by (partial) label; without it,
     * whichever nav app was used last, or the only one installed, or the first one found. Starts
     * it straight into navigating there via [CarAppConnection]'s `ACTION_NAVIGATE` deep link -
     * the same one Android Auto's own Assistant integration uses - rather than opening the app
     * and leaving the driver to type the address in on the car keyboard.
     */
    private suspend fun navigateCommand(
        context: Context,
        navigator: CarNavigator,
        frame: CarEnvironment.Frame,
        heard: String,
    ): String? {
        // "use Organic Maps to get to X", "open OsmAnd and navigate to X": the app comes first.
        // "take me home", "go home": no "to", so no trigger would catch them.
        var text = if (Regex("^(?:(?:take|bring|get|drive) me|go|drive|head|get|directions|go back|head back) home$").matches(heard)) {
            "navigate to home"
        } else {
            heard
        }
        var leadingApp: String? = null
        Regex("^(?:use|using|with|open|in|on) (.+?) (?:to |and )(.+)$").find(text)?.let { m ->
            if (after(m.groupValues[2], NAVIGATE_TRIGGERS) != null) {
                leadingApp = m.groupValues[1]
                text = m.groupValues[2]
            }
        }
        var destination = after(text, NAVIGATE_TRIGGERS) ?: return null
        if (destination.isBlank()) return "Where to?"

        val entries = allEntries(context)
        // "go to Spotify" means the app, and "go to settings" the screen; "go to Target" is a place.
        val asApp = destination.removePrefix("the ").removeSuffix(" app")
        if (text.startsWith("go to ") &&
            (asApp in SCREEN_WORDS || asApp in APP_NICKNAMES || entries.any { it.label.lowercase() == asApp })
        ) {
            return null
        }
        val navApps = entries.filter { it.template?.isNavigation == true }
        if (navApps.isEmpty()) return "You don't have any navigation apps installed."

        // "X with Organic Maps", "X on OsmAnd": split only when the end names a nav app, so a
        // place like "Starbucks on Main Street" stays whole.
        var chosenApp = leadingApp?.let { name -> bestMatch(navApps, name) { it.label } ?: return "I couldn't find a navigation app called $name." }
        if (chosenApp == null) {
            for (sep in listOf(" with ", " using ", " on ", " via ", " in ", " through ")) {
                val at = destination.lastIndexOf(sep)
                if (at <= 0) continue
                val app = bestMatch(navApps, destination.substring(at + sep.length)) { it.label } ?: continue
                chosenApp = app
                destination = destination.substring(0, at).trim()
                break
            }
        }
        // "the nearest gas station" -> "gas station": the map app already searches around the car
        // and AutoRoute picks the nearest result, and Organic Maps took "nearest" as part of the name.
        val (place, nearby) = plainPlace(destination)
        destination = place
        if (destination.isBlank()) return "Where to?"
        // "home", "work", or a name from Saved places: its address is the search.
        var spoken: String? = null
        SavedPlaces.match(context, destination)?.let { (slot, saved) ->
            val name = when (slot) {
                SavedPlaces.HOME -> "home"
                SavedPlaces.WORK -> "work"
                else -> saved.label
            }
            if (saved.address.isBlank()) {
                return "You haven't saved a $name address yet. Add it under Saved places in Gearslip's settings on your phone."
            }
            destination = saved.address
            spoken = if (slot == SavedPlaces.HOME) "home" else "to $name"
        }

        val chosen = chosenApp ?: when {
            navApps.size == 1 -> navApps.first()
            else -> navApps.firstOrNull { it.componentId == CarSettings.lastNav.value } ?: navApps.first()
        }
        val template = chosen.template ?: return "That's not a navigation app."

        CarServices.connectNav(template, frame, destination)
        navigator.home()
        spoken?.let { return "Navigating $it with ${chosen.label}." }
        return if (nearby) "Finding the nearest $destination with ${chosen.label}." else "Navigating to $destination with ${chosen.label}."
    }

    /** [destination] without "a", "the nearest", "near me" and the like, and whether any of that said "close by". */
    private fun plainPlace(destination: String): Pair<String, Boolean> {
        var t = destination
        var nearby = false
        var changed = true
        while (changed) {
            changed = false
            for (lead in PLACE_LEADS) if (t.startsWith(lead)) {
                t = t.removePrefix(lead).trim(); changed = true
                if (lead.trim() !in ARTICLES) nearby = true
            }
            for (tail in PLACE_TAILS) if (t.endsWith(tail)) { t = t.removeSuffix(tail).trim(); changed = true; nearby = true }
        }
        return t to nearby
    }

    /**
     * "play X" with something after it: a song, artist, album or playlist, asked of the music app
     * that's open through its own search ([CarMedia.search]). A bare "play", or "play music",
     * falls through to resume.
     */
    private suspend fun playSearchCommand(context: Context, navigator: CarNavigator, heard: String): String? {
        var query = after(heard, PLAY_TRIGGERS) ?: return null
        query = query.removePrefix("some ").trim()
        if (query.isEmpty() || query in RESUME_WORDS) return null
        val media = CarServices.media

        // "play Daft Punk on Metrolist": switch to that app first, if it's one we know.
        for (sep in listOf(" on ", " in ", " from ", " with ", " using ")) {
            val at = query.lastIndexOf(sep)
            if (at <= 0) continue
            val apps = withContext(Dispatchers.IO) { app.seb3thehacker.gearslip.media.MediaCatalog.installed(context) }
            val wanted = bestMatch(apps, query.substring(at + sep.length)) { it.label } ?: continue
            query = query.substring(0, at).trim()
            if (query.isEmpty() || query in RESUME_WORDS) {
                CarServices.openMedia(wanted, autoplay = true)
                navigator.media()
                return "Opening ${wanted.label}."
            }
            if (CarServices.mediaApp.value?.component != wanted.component) {
                CarServices.openMedia(wanted)
                navigator.media()
                // Give it a moment to connect before asking it to search.
                withTimeoutOrNull(APP_CONNECT_MS) {
                    while (media.appLabel == null || (!media.canSearchLibrary && !media.now.value.canSearch)) delay(200)
                }
            }
            break
        }

        // No music app open, or its connection is gone (the app was closed): use the one played
        // last, the way "play X" works on Android Auto.
        val phase = media.phase.value
        if (media.appLabel == null || phase == CarMedia.Phase.IDLE || phase == CarMedia.Phase.REJECTED) {
            if (!CarServices.reopenLastMedia()) return "Open a music app first, then ask again."
            navigator.media()
        }
        withTimeoutOrNull(APP_CONNECT_MS) {
            while (media.phase.value != CarMedia.Phase.READY || media.appLabel == null ||
                (!media.canSearchLibrary && !media.now.value.canSearch)
            ) delay(200)
        }
        val app = media.appLabel ?: return "Your music app didn't open in time. Try again."
        if (!media.canSearchLibrary && !media.now.value.canSearch) {
            return "$app ignores voice searches. Pick something on its screen instead."
        }
        media.playBestMatch(query)
        return "Looking for $query on $app."
    }

    private fun mediaCommand(heard: String): String? {
        val media = CarServices.media
        return when {
            "pause" in heard || heard.startsWith("stop") || heard == "quiet" || heard == "shut up" -> {
                media.pause()
                VoiceFocus.afterRelease { media.pause() }
                "Paused."
            }
            "resume" in heard || "unpause" in heard || heard in RESUME_PHRASES ||
                (heard.startsWith("play") && "playing" !in heard) -> {
                if (!media.now.value.playing) media.togglePlay()
                "Playing."
            }
            "next" in heard || "skip" in heard || heard.startsWith("change the song") -> {
                media.next()
                "Skipping."
            }
            "previous" in heard || "last song" in heard || "back song" in heard || "go back a song" in heard ||
                "play that again" in heard || "replay" in heard -> {
                media.previous()
                "Playing the previous track."
            }
            ("what" in heard && "playing" in heard) || "what song" in heard || "who sings" in heard ||
                "who's singing" in heard || "who is singing" in heard || "what's this song" in heard ||
                "name of this song" in heard -> {
                val now = media.now.value
                if (now.isActive) listOf(now.title, now.artist).filter { it.isNotBlank() }.joinToString(" by ")
                else "Nothing's playing."
            }
            else -> null
        }
    }

    private fun navCommand(navigator: CarNavigator, heard: String): String? = when {
        heard == "go back" || heard == "back" -> {
            navigator.back()
            "Going back."
        }
        heard in EXIT_WORDS -> {
            BuiltInApps.Exit.open(navigator)
            "Back to the car's screen."
        }
        "go home" in heard || heard == "home" || heard == "home screen" || "go to the home screen" in heard -> {
            navigator.home()
            "Heading home."
        }
        "open apps" in heard || heard == "apps" || "app list" in heard || "all apps" in heard || "my apps" in heard || heard == "go to apps" -> {
            navigator.apps()
            "Here are your apps."
        }
        "settings" in heard -> {
            navigator.settings()
            "Opening settings."
        }
        "vehicle data" in heard || "car data" in heard -> {
            if (CarSettings.experimentalFeaturesEnabled.value && CarSettings.carSensorsEnabled.value) {
                navigator.vehicleData()
                "Opening vehicle data."
            } else {
                "Vehicle data is off. Turn it on under experimental features in settings."
            }
        }
        else -> null
    }

    private fun infoCommand(heard: String): String? = when {
        "what time" in heard || "what's the time" in heard || heard == "time" || "the time" in heard ->
            "It's ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())}."
        "weather" in heard || "going to rain" in heard || "gonna rain" in heard || "how hot" in heard ||
            "how cold" in heard || "temperature outside" in heard -> {
            val data = (Weather.state.value as? WeatherState.Ready)?.data
            if (data != null) "${data.temp}${data.tempUnit} and ${Weather.describe(data.code)} in ${data.place}."
            else "I don't have the weather yet."
        }
        else -> null
    }

    private suspend fun callCommand(context: Context, heard: String): String? {
        val name = (
            after(heard, CALL_TRIGGERS)
                ?: Regex("^give (.+) a (?:call|ring)$").find(heard)?.groupValues?.get(1)
                ?: return null
            ).replace(Regex(" (?:on|at) (?:their |his |her )?(?:mobile|cell|phone|home|work)$"), "").trim()
        if (name.isBlank()) return "Who should I call?"
        if (!hasCallPermissions(context)) return "Gearslip needs the phone and contacts permissions first, from the phone screen."

        val match = withContext(Dispatchers.IO) {
            loadContacts(context).firstOrNull { it.name.lowercase().contains(name) }
        } ?: return "I couldn't find $name in your contacts."

        placeCall(context, match.number)
        return "Calling ${match.name}."
    }

    private suspend fun openAppCommand(
        context: Context,
        navigator: CarNavigator,
        frame: CarEnvironment.Frame,
        heard: String,
    ): String? {
        val name = (after(heard, OPEN_TRIGGERS) ?: return null)
            .removePrefix("the ").removePrefix("my ").removeSuffix(" app").trim()
        if (name.isBlank()) return null
        val wanted = APP_NICKNAMES[name] ?: name

        // Gearslip's own apps come first in the list, so "phone" opens its dialer, not the phone's.
        val match = bestMatch(allEntries(context), wanted) { it.label }
            ?: return "I couldn't find an app called $name."

        launchEntry(match, navigator, frame)
        return "Opening ${match.label}."
    }

    private val EXIT_WORDS = setOf("exit", "exit gearslip", "close gearslip", "quit", "quit gearslip", "open exit")

    /** Other names people use for Gearslip's own apps. */
    private val APP_NICKNAMES = mapOf(
        "web" to "browser", "internet" to "browser", "the web" to "browser", "web browser" to "browser",
        "dialer" to "phone", "the dialer" to "phone", "keypad" to "phone",
        "screen share" to "screen sharing", "screen mirroring" to "screen sharing", "mirroring" to "screen sharing",
    )

    private val NAVIGATE_TRIGGERS = listOf(
        "navigate to ", "navigate me to ", "directions to ", "get directions to ", "give me directions to ",
        "take me to ", "drive to ", "drive me to ", "get me to ", "go to ", "head to ", "route to ",
        "how do i get to ", "show me the way to ", "find a route to ", "navigation to ", "get to ",
        // Last, so "navigate to" wins: the recognizer sometimes drops the "to".
        "navigate me ", "navigate ", "find me the nearest ", "find the nearest ", "find the closest ",
    )

    private val ARTICLES = setOf("a", "an", "the", "some")
    // Longest first, so "the nearest " goes before "the ".
    private val PLACE_LEADS = listOf(
        "the nearest ", "the closest ", "a nearby ", "the nearby ", "nearest ", "closest ", "nearby ",
        "a ", "an ", "some ",
    )
    private val PLACE_TAILS = listOf(" near me", " nearby", " near here", " around here", " close by", " closest to me")

    private val PLAY_TRIGGERS = listOf("play me ", "play ", "put on ", "listen to ", "i want to hear ", "shuffle ")

    private val CALL_TRIGGERS = listOf("call ", "phone ", "dial ", "ring ", "make a call to ", "place a call to ", "start a call with ")

    private val OPEN_TRIGGERS = listOf("open ", "launch ", "start ", "go to ", "switch to ", "show me ", "bring up ", "pull up ")

    /** Screens "go to X" means rather than a place. */
    private val SCREEN_WORDS = setOf("settings", "home", "the home screen", "home screen", "apps", "my apps", "all apps")

    /** Padding stripped from the front of a command, longest first where they overlap. */
    private val LEAD_FILLERS = listOf(
        "hey gearslip", "ok gearslip", "okay gearslip", "gearslip", "hey", "ok", "okay", "um", "uh", "so",
        "please", "can you please", "can you", "could you", "would you", "will you", "can i", "could i",
        "i want you to", "i want to", "i'd like to", "i would like to", "i wanna", "i need to", "i need you to",
        "let's", "lets", "go ahead and", "just", "now", "quickly",
    )

    /** Longest first, so "right now" goes whole rather than leaving "right". */
    private val TAIL_FILLERS = listOf("thank you very much", "thank you", "right now", "for me", "please", "thanks", "now")

    private const val APP_CONNECT_MS = 5_000L

    private val HELP_WORDS = setOf("help", "commands", "voice commands", "list commands", "show commands")

    private val RESUME_WORDS = setOf("music", "the music", "some music", "something", "it", "again", "that", "my music")

    private val RESUME_PHRASES = setOf("keep playing", "continue", "keep going", "start the music", "music on", "turn the music back on")
}
