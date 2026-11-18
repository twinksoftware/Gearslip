# Nothing OS car UI — port plan

The interactive mockups (`carplay-nothing-preview.html`, `carplay-nothing-final.html`) define the
target: CarPlay's information architecture rendered in the Nothing OS visual language already
established in `car/theme/Nothing.kt` and `design.md`.

## Decisions

- **Local music is out of scope.** The first-party music layer uses the app's existing media
  session / third-party media apps; it does not scan local audio files.
- **Third-party apps remain optional.** The Apps screen keeps hosted car apps and media apps
  alongside the built-in screens. The built-in music and navigation screens are additions, not
  replacements.
- **Echo Music and Vela are design references only.** Both are GPL-3.0; no source is copied.
  Ideas absorbed: browse/search/library shelves, synced lyrics and queue presentation, Media3
  session as the single playback source; place search with nearby categories, offline-index
  framing, route GO actions and active-turn chrome.
- **Nothing style is mandatory on every new screen**: pure black canvas, 1px borders, no shadows
  or rounded cards, Doto display type, dot-grid glyphs, one red accent (`#FF3030`).

## Screens to add / restyle

| Screen | Source in mockup | Compose target |
|---|---|---|
| Maps / active nav | nav page | existing Home map pane + Nothing turn chrome |
| Place search | search page | new first-party screen |
| Music browse / search / library | browse page | new first-party screen |
| Full player + lyrics / queue | player page | new first-party screen over existing `NowPlaying` |
| Apps | apps page | existing `CarLauncher`, Nothing tiles already present |
| Weather | weather page | existing `WeatherScreen`, Nothing styling |
| Vehicle | vehicle page | existing `VehicleDataScreen`, Nothing styling |
| Screensaver | saver page A–F | `CarScreensaver` case matrix |

## Screensaver case matrix

| Case | Music | Direction | Behaviour |
|---|---|---|---|
| A | playing | set | album-colour ambient wash, animated dot-matrix left arrow + distance at bottom |
| B | playing | none | ambient wash, track + visualiser, no arrow |
| C | none | set | pure black, animated arrow + distance |
| D | none | none | pure black, dot-matrix clock, breathing dot, TOUCH TO WAKE |
| E | paused | set | arrow active, track dimmed, PAUSED chip |
| F | playing | imminent turn | red pulsing arrow + distance |

Every case must wake on touch or any car key.

## Verification

Local builds are blocked (no SDK platform installed). Every change is verified by the fork's CI:
`./gradlew :gearslip:assembleDebug`, CodeQL and Security scan. A release is published with the APK
on a green build.
