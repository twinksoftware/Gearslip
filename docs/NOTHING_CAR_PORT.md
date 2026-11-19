# Nothing OS car UI — port plan

The interactive mockups (`carplay-nothing-preview.html`, `carplay-nothing-final.html`) define the
target: CarPlay's information architecture rendered in the Nothing OS visual language already
established in `car/theme/Nothing.kt` and `design.md`.

Gearslip no longer streams music itself. It is a Nothing-styled front-end that drives external
media apps — Vivi Music and Echo Music — through Android's MediaBrowser/MediaController APIs. The
Echo InnerTube catalog and the bounded-range `ChunkedDataSource` were ported in and then removed;
the attribution history is kept in `NOTICE.md`.

## Decisions

- **Local music is out of scope.** The car's music screens control an external media app's session
  through MediaBrowser/MediaController; they do not scan local audio files or resolve stream URLs.
- **Third-party apps remain optional.** The Apps screen keeps hosted car apps and media apps
  alongside the built-in screens. The built-in music and navigation screens are additions, not
  replacements.
- **Music comes from external apps, not a first-party catalog.** Vivi Music and Echo Music are
  resolved by their exported MediaBrowserService components (including `.debug` builds) and
  controlled through MediaBrowser/MediaController. The earlier port of Echo Music's InnerTube
  catalog/search client and metadata cleaner into the `:catalog` module, and of the bounded-range
  `ChunkedDataSource` into `gearslip/.../music/playback`, has been removed (GPL-3.0, attribution in
  `NOTICE.md`); no Echo-derived source remains in the tree. Browse, Search and Player are
  re-implemented for the car canvas in the Nothing style over the external app's session. Ideas
  absorbed from that work: browse/search/library shelves, synced lyrics and queue presentation;
  place search with nearby categories, offline-index framing, route GO actions and active-turn
  chrome. Vela remains a design reference only.
- **The external app owns playback and stream reliability.** Gearslip no longer resolves stream
  URLs, bounds ranged chunks or re-resolves a failed URL — that is the responsibility of the media
  app being driven. (The removed first-party stack used the plain ANDROID InnerTube client rather
  than ANDROID_VR because googlevideo serves only the first ~512 KB of a VR-minted URL.)
- **Nothing style is mandatory on every new screen**: pure black canvas, 1px borders, no shadows
  or rounded cards, Doto display type, dot-grid glyphs, one red accent (`#FF3030`).

## Screens to add / restyle

| Screen | Source in mockup | Compose target |
|---|---|---|
| Maps / active nav | nav page | existing Home map pane + Nothing turn chrome |
| Place search | search page | new first-party screen |
| Music browse / search / library | browse page | Nothing-styled front-end over the external app's MediaBrowser tree |
| Full player + lyrics / queue | player page | Nothing-styled player controlling the external app's MediaController |
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
