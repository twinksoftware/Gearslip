Gearslip 0.3.39 runs as a head unit's home screen, connects to more cars, and brings Google Maps and Waze turns to the car.

This is a pre-release. Many of these fixes target cars we don't own, so please try it and tell us how your car does. Attach a log to your issue: open Gearslip, tap Logs, then Share log.

## New
- Launcher mode: on a head unit that runs Android, or a spare phone or tablet on the dash, Gearslip can be the home screen. Turn it on under Settings > Connection. An All apps page opens everything else.
- Turns from Google Maps or Waze on the phone show in the car's nav bar and on its instrument cluster.
- An Apps card on the phone's home screen lets you hide apps from the car's drawer. Thanks to @johnneerdael.
- A display density setting makes text and buttons bigger in car apps on small screens. Thanks to @johnneerdael.
- A Language setting picks Gearslip's language, and spoken directions use a voice in that language.
- Alerts from car apps show on the car screen, such as Waze asking whether a hazard is still there.
- AntennaPod gets a Next chapter button.
- The car list shows the names you gave your Bluetooth devices. Thanks to @theo330.
- A car you once turned away can connect on its own again, from the car list.

## Fixed
- Cars that waited for the music channel before showing a picture now show Gearslip's screen. This should fix the black screen or endless "Starting video" on the 2017 Pilot, 2020 Civic, 2021 CX-5 and 2024 Mirage.
- Gearslip sends the car its icon and a session ID, as a real phone does. Older Hyundais that stopped after the first request should now connect.
- Gearslip pings the car once a second, as a real phone does. This may stop the 2021 Bolt from hanging up after 25 seconds.
- A still car screen gets a fresh frame every second, which should fix the black screen on some aftermarket boxes.
- Wireless keeps calling the car until it answers, starts over after a failed step, and stays connected with the phone locked.
- Music playing through Gearslip comes back after a call.
- Car maps keep the phone's position while the phone is locked.
- Spoken directions start the speech engine again when it fails to start.
- Error messages say when the car's clock is wrong, and what to try when a connection gets stuck.

## For a few cars
- Directions over the music channel, under Spoken directions in the car's settings: for a car that drops the connection or restarts after a spoken turn.
- Phone's Bluetooth address, under Settings > Connection: for a car that drops the phone's Bluetooth when Gearslip connects. Copy the address from Android's Settings > About phone.

New text is in English until translators catch up. Help with your language on [Weblate](https://hosted.weblate.org/engage/gearslip/).
