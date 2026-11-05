Gearslip 0.3.40 is a test build for a few cars that still don't connect.

If your car works on 0.3.39, you don't need this one. If you're following #18, #39 or #57, please try it and attach a log to your issue: open Gearslip, tap Logs, then Share log.

## Fixed
- A car that holds back its screen is asked for it again with the detail older cars expect. This is for the 2017 Pilot (#57) and other cars on the oldest Android Auto version.
- Music no longer carries on from the phone's speaker after you turn the car off. An app that started playing again by itself is paused a second time (#39).
- A liked song lights up the whole like button, as shuffle and repeat do. Before, only the heart filled in for apps such as Metrolist.

## Testing
- GM cars open two more channels, Bluetooth and Wi-Fi projection, and log what the car sends on them. This is to find out why the 2021 Bolt hangs up about 20 seconds in (#18). If you drive a GM car, type your phone's Bluetooth address under Settings > Connection > Phone's Bluetooth address. You'll find it in Android's Settings under About phone.

Help translate Gearslip on [Weblate](https://hosted.weblate.org/engage/gearslip/).
