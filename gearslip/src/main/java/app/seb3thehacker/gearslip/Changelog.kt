package app.seb3thehacker.gearslip

/**
 * What changed in each release, newest first, shown on the car screen after an update (and on
 * the phone). Only the changes a driver would notice. Plain words, short lines: this is read in
 * a parked car.
 *
 * After 0.3.40, the version code is the one the build works out from the tag (see gitVersion in
 * build.gradle.kts): ((major*100 + minor)*100 + patch)*10 + stage, so 0.4.0-beta is 4002. Older
 * entries keep the hand-set codes they shipped with, which are all lower.
 */
object Changelog {

    class Release(val versionCode: Int, val versionName: String, val changes: List<String>)

    val releases = listOf(
        Release(
            15, "0.3.40-alpha",
            listOf(
                "A liked song lights up the whole like button, as shuffle and repeat do.",
                "Music no longer carries on from the phone after you turn the car off.",
                "Cars that hold back their screen are asked for it again in a way older cars understand.",
            ),
        ),
        Release(
            14, "0.3.39-alpha",
            listOf(
                "Turns from Google Maps or Waze on the phone show in the nav bar and on the car's cluster.",
                "Alerts from car apps show here, such as Waze asking whether a hazard is still there.",
                "Hide apps you don't use in the car from the Apps card on the phone.",
                "Make text in car apps bigger with Car display density, under Screen.",
                "Music playing through Gearslip comes back after a call.",
                "Maps keep your position while the phone is locked.",
                "Wireless keeps trying until the car answers.",
            ),
        ),
        Release(
            9, "0.3.00-alpha",
            listOf(
                "Wireless Android Auto: pair the phone with the car once, and leave the cable at home.",
                "Voice control is built in. Say \"navigate to a gas station\", \"play\" a song or artist, or open an app by name.",
                "\"Navigate to\" picks the nearest place and stops at the map app's start screen, so you only tap Start.",
                "Gearslip turns speech into text on the phone, with no Google account. Download the voice models once in setup.",
                "The car's rotary knob moves between buttons, so cars without a touchscreen work too.",
                "Choose how music reaches the car: Bluetooth, or USB with no delay. Gearslip offers to pair with the car the first time.",
                "The car speaks each turn from your map app, and shows the directions on its own display where it can.",
                "The car shows what's playing, and music apps get their own like, shuffle and repeat buttons.",
                "Pick how the car's buttons look. Change it any time in the car's settings.",
                "A new destination mid-route replaces the old route, and switching map apps ends the old app's route.",
                "The car warns you when the phone's location is off.",
                "Gearslip finds new versions and installs them for you.",
                "More cars play music over USB, show a sharper picture, and put taps where you press.",
                "On GrapheneOS, Gearslip no longer needs exploit protection compatibility mode.",
                "The app is half the size it was.",
            ),
        ),
        Release(
            8, "0.2.00-alpha",
            listOf(
                "Exit takes you to the car's own screen. Tap Android Auto on the car to come back.",
                "Gearslip listens through the car's microphone when the car has one, so voice works with the phone in a pocket.",
                "A voice reply sends when its bar runs out, even over road noise or the radio. Say \"cancel\" or \"change it\" before then.",
                "Maps turn dark when the car switches to night mode.",
                "A moving map no longer makes the car screen fall behind your taps.",
                "Gearslip asks to send sound to the car as soon as you connect, so videos in the web browser play in the car too.",
                "Cars that offer more than one kind of music audio, such as the 2022 Dacia Jogger, play sound again.",
                "When the car shows a keyboard, you can type on the phone instead. The car's keyboard is taller too.",
                "Every car screen has a back button.",
                "App icons show whether an app works in Gearslip. Metrolist works and shows the green check.",
                "LIVI connects again, and touch works on cars with a second screen.",
                "The phone's Settings screen has a new layout, with What's new and the setup guide at the top.",
                "You can choose to send short usage notes that help the project. They hold no name, account, or location, and they're off unless you turn them on.",
                "Searching inside a media app moved to Settings > Experimental features.",
                "Under Settings > Media audio, the choices are now Phone audio and Car audio.",
                "Logs leave out your location and the car's serial number.",
            ),
        ),
        Release(
            7, "0.1.10-alpha",
            listOf(
                "The launcher groups your apps: Phone, Maps, Music, Messaging, Web, Screen sharing, and Settings.",
                "To move an app, press and hold it on the launcher, then tap Move left or Move right.",
                "MapQuest and Flitsmeister work in Gearslip and show the green check.",
                "Spotify has one tile. If Spotify isn't open on the phone, tap Open Spotify to start it.",
                "Flick a map and it glides on, as it does in Android Auto.",
                "The map picks up where it left off when you come back from another screen.",
                "Search boxes in map apps are larger and easier to read.",
                "Map apps keep the right icons on their buttons after they update.",
                "When Gearslip asks \"Do you want to reply?\", a bar shows how long it will listen. The bar stops while you speak.",
                "Gearslip fits its picture to wide car screens and keeps it clear of the edges the car crops.",
                "Gearslip answers the car's connection checks correctly. Some cars, Audis among them, hung up after a second without this.",
                "Google Maps no longer shows up as a messaging app.",
            ),
        ),
        Release(
            6, "0.1.0-alpha",
            listOf(
                "Messaging apps such as Signal and Molly open to your recent chats.",
                "Gearslip reads a message aloud, and you reply by voice. Say \"cancel\" or \"change it\" before it sends, or tap a quick reply such as your arrival time.",
                "Voice works on any phone, GrapheneOS included. Gearslip turns speech into text itself, so it needs no Google account.",
                "New messages show as a small card at the top right, even while you drive. A bar shows how long until the card closes or the reply sends.",
                "Only messages, missed calls, alarms, and reminders pop up. Everything else waits on the dashboard.",
                "The map stays loaded when you switch screens, so it no longer flashes black.",
                "Map apps draw more reliably and keep their connection when the phone is locked.",
                "The Home button shows your map app, so it no longer appears twice.",
                "The next turn shows in the nav bar when you leave the map.",
                "Steering wheel buttons, control knobs, and D-pads now work. If yours don't, open an issue on GitHub.",
                "To see the whole song title and artist in the nav bar, choose Settings > Player in the nav bar > Song title.",
                "Choose miles or kilometres under Settings > Units.",
                "Try the voice assistant and the vehicle data screen under Settings > Experimental features.",
                "Logs now say why a car ended the connection.",
                "Spotify, Waze, and Google Messages updated to a version of Google's car library that only accepts Android Auto itself. Their own car screens won't open in Gearslip. Spotify still plays through the Gearslip player, which has the green check.",
                "Calls go through the car's Bluetooth, as they do in Android Auto. Pair your phone with the car to hear calls on its speakers.",
            ),
        ),
    )

    /** The release that matches this build, if it has notes. */
    val current: Release? get() = releases.firstOrNull { it.versionCode == BuildConfig.VERSION_CODE }
}
