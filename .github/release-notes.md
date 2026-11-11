Gearslip 0.4.0 brings a web browser with ad blocking, a map behind place lists, any music app or messenger in the car, and a cleaner player beside the map.

## New
- The car's web browser opens on a start page with bookmark tiles, the search engine you choose, and ad blocking with EasyList and EasyPrivacy (#85). Manage bookmarks in the car or on the phone.
- Add any installed app to the car as a music app or a messenger, under Car > Apps > Add an app (#84).
- Apps that list places, such as RF Sentinel, show them on a map, as Android Auto does.
- Download a certificate from a link, as a .p12 file or as PEM or DER, and fetch it again later from Settings (#15).
- Car display density takes any value from 60 to 480 DPI, under Screen > Custom (#82).
- Hide the nav bar's mic button. The car's voice key still works.
- Phone in the car opens on recent calls, with missed calls marked.
- Today's weather card shows the next few hours.

## Player and navigation
- With the player beside the map, like, shuffle, repeat, and lyrics sit in the nav bar, and the dock button there closes the player.
- The player's buttons fit narrow screens, and the song's times show beside the map and in larger type on the media screen.
- The trip's time, arrival, and distance have their own card at the bottom again.
- The cover stays steady instead of dimming every few seconds.
- Waze's Go button counts down and starts the drive by itself.

## Fixed
- Map apps keep their GPS fix with the phone locked (#99). Gearslip asks for location "Allow all the time" while a car is connected, and uses it only then.
- Gearslip tells Honda cars which phone it is, so they hand over their screen, and sends sound in bigger chunks to cars that take it slowly (#86, #87). Thanks to @davideresigotti.
- A car that hangs up during setup gets a shorter request the next time (#91).
- The car screen follows the language you pick for Gearslip (#80).
- The music browser handles apps that list the same song twice (#90).
- Screen sharing stops when the car leaves (#88).
- Apps known not to work show a red X and start unticked, in the car and in the phone's app list. Patched copies that work, such as Waze through Morphe, show none.

## Changed
- The car audio choice moved to Settings > Connection. With Through Gearslip, map apps' spoken directions play on the phone.
- Connecting wirelessly with Wi-Fi off now asks you to turn it on (#102). Thanks to @grmasa.

## Privacy
Gearslip now has a [privacy policy](https://github.com/twinksoftware/gearslip/blob/main/PRIVACY.md). It lists every service Gearslip contacts and what each one receives.

Help translate Gearslip on [Weblate](https://hosted.weblate.org/engage/gearslip/).
