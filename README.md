[![Build APK and release](https://github.com/shrijit37/Gearslip/actions/workflows/release.yml/badge.svg)](https://github.com/shrijit37/Gearslip/actions/workflows/release.yml)

# Gearslip — Nothing OS car UI

A standalone, Nothing OS-styled replacement for the Android Auto car interface. It speaks
the Android Auto protocol directly to a car over USB or wireless, with no Google software in
the path, and draws its own monochrome car shell on top.

> [!IMPORTANT]
> This is a standalone build, not a fork of upstream Gearslip in look or feature set. It keeps
> the car connection protocol and hosts third-party car apps, but the interface is a fixed
> Nothing OS-inspired skin: a left app rail, a stage, and a compact player. It is not
> affiliated with Nothing Technology Limited or Twink Software LLC. The protocol work began in
> [twinksoftware/Gearslip](https://github.com/twinksoftware/Gearslip).

> [!WARNING]
> This is alpha software. It can drop the connection, freeze the car screen, or fail to start.
> Set it up while parked and keep another way to navigate.

## What it does

| | |
|---|---|
| **Cars** | Most cars over USB or wireless. Some cars from about 2020 need a certificate of your own. [CARS.md](CARS.md) lists confirmed cars. |
| **Maps** | Third-party car navigation apps (Organic Maps, Vela, OsmAnd, Waze and others) render on the car stage. Place search is built in with an offline demo index and saved places; Google Maps can be mirrored from the phone. |
| **Music** | First-party Browse, full player with synced lyrics and queue, plus any car-ready media app as an option. |
| **Weather** | Current conditions, hourly and weekly forecast, with a dashboard showing the day ahead. |
| **Vehicle data** | Supported car sensor readings, shown raw. |
| **Controls** | Touch, steering-wheel buttons, control knobs and D-pads. |
| **Screensaver** | Rests on the dot-matrix clock, next turn, playback state and car readings. |

The shell is deliberately narrow: Nothing Red is the only accent, surfaces are pure black,
borders are 1px, and display text uses the bundled Doto font. See [design.md](design.md) for
the tokens and the [mockup](carplay-nothing-final.html) for the intended layout.

## Install

1. Install an APK from the [releases page](https://github.com/shrijit37/Gearslip/releases).
2. Open it and follow setup: choose a certificate source, grant each permission with its reason,
   then read the safety note.
3. Plug into the car's USB port. For wireless cars, pair over Bluetooth, tap **Connect
   wirelessly**, and pick the car.

Releases are **debug builds** signed by the CI runner's throwaway debug key, so a new release
may need an uninstall before installing. There is no fixed fingerprint to compare; get APKs
only from the releases page.

> [!NOTE]
> On GrapheneOS, don't set **USB-C port control** to **Charging-only** — it blocks USB data, so
> the car never sees the phone.

## Build

`./gradlew :gearslip:assembleDebug` produces `build/Gearslip-debug.apk`. CI builds, checks and
publishes each tagged commit. A local Android SDK with platform 37 is required.

## License

Licensed under AGPL-3.0; see [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md).
