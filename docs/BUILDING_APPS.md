# Building apps for Gearslip

Gearslip renders four kinds of app on a car screen. This page specifies all four.

| Kind | What it is | Status |
| --- | --- | --- |
| [Templated apps](#templated-apps) | An AndroidX Car App Library app. Gearslip hosts it. | Works today |
| [Media apps](#media-apps) | A `MediaBrowserService` that opted in to Android Auto. | Works today |
| [Messaging apps](#messaging-apps) | An app whose notifications opted in to Android Auto. | Works today |
| [Gearslip apps](#gearslip-apps) | A native Gearslip screen, described over binder. | Specified here; not yet implemented |

Write a templated app when your app fits the Car App Library's templates, because it runs today
and it also runs on Android Auto. Write a media app when you ship audio. Write a Gearslip app
when you want a screen the Car App Library cannot describe.

Gearslip's package name is `app.seb3thehacker.gearslip`. It declares
`android.car.permission.TEMPLATE_RENDERER`.

---

## Templated apps

A templated app exports a `CarAppService`, describes each screen as a `Template`, and lets the
host draw it. Gearslip implements the host half of that protocol, so an existing Android Auto app
needs no Gearslip-specific code. Build it against the AndroidX Car App Library as you would for
any other host.

### Letting Gearslip in

Your app decides which hosts may render it, through the `HostValidator` your `CarAppService`
returns.

Gearslip declares `android.car.permission.TEMPLATE_RENDERER`. Car App Library 1.8.0-beta01
and earlier, and 1.9.0-alpha01, accept any host holding it, so Gearslip passes the default
validator. From 1.8.0-rc01 and 1.9.0-alpha02 the library checks that permission only on
Android Automotive, so on a phone Gearslip passes only when your allowlist names it. Add this
entry:

```xml
<!-- res/values/arrays.xml -->
<array name="hosts_allowlist">
    <item>4cc56ebdd13a54d260eca9b7e2b2ae6b30e2f225bfda8f508f0709edf084948b,app.seb3thehacker.gearslip</item>
</array>
```

```java
@Override
public HostValidator createHostValidator() {
    return new HostValidator.Builder(getApplicationContext())
            .addAllowedHosts(R.array.hosts_allowlist)
            .build();
}
```

That digest belongs to Gearslip's release signing certificate. A Gearslip you built yourself
carries your own debug key and a different digest, so read the fingerprint off the APK you
actually installed when an allowlist entry fails to match.

Write each entry as the digest first, then a comma, then the package name. The digest is the
SHA-256 of the host's signing certificate as 64 lowercase hex characters, with no colons and no
`0x` prefix. The builder lowercases the entry and strips spaces, but it keeps colons, so convert
`keytool` output before pasting it:

```
keytool -list -v -keystore <keystore> -alias <alias> \
  | grep 'SHA256:' | cut -d' ' -f3 | tr -d ':' | tr 'A-Z' 'a-z'
```

Reading the installed APK is more reliable, because it fingerprints the key that actually signed
the build, and `apksigner` already prints the required format:

```
apksigner verify --print-certs Gearslip.apk | grep 'SHA-256 digest'
```

During development, `HostValidator.ALLOW_ALL_HOSTS_VALIDATOR` accepts every host. Ship a real
validator instead.

An app that refuses a host returns no binder at all. Gearslip reports that as
`the app refused to bind - host not accepted`. Your own side logs the reason under the tag
`CarApp.Val`, including the exact `addAllowedHost` call to add, so read logcat there first.

### Discovery

Gearslip finds templated apps by querying `androidx.car.app.CarAppService`, then re-querying with
each known category to recover which ones your intent filter matched. Declare the categories you
belong to, or Gearslip files you under `app`:

```xml
<service android:name=".MyCarAppService" android:exported="true">
    <intent-filter>
        <action android:name="androidx.car.app.CarAppService" />
        <category android:name="androidx.car.app.category.NAVIGATION" />
    </intent-filter>
</service>
```

Gearslip recognises the `NAVIGATION`, `POI`, `PARKING`, `CHARGING`, `MESSAGING`, `CALLING`,
`SETTINGS`, `IOT` and `WEATHER` categories. Navigation apps start automatically on the home
screen when the driver used them last.

### The handshake

Gearslip offers car API level 8. It negotiates down to
`min(8, yourLatestCarAppApiLevel)` and refuses the app when that falls below your
`minCarAppApiLevel`, rather than drawing it wrongly. Keep your minimum at 8 or below.

### Templates Gearslip renders

Gearslip renders all sixteen templates. Ten describe their own content:

`ListTemplate`, `GridTemplate`, `PaneTemplate`, `MessageTemplate`, `LongMessageTemplate`,
`SearchTemplate`, `SignInTemplate`, `TabTemplate`, `SectionedItemTemplate`,
`MediaPlaybackTemplate`.

Six draw a map underneath and take chrome over the top:

`NavigationTemplate`, `MapWithContentTemplate`, `MapTemplate`, `PlaceListNavigationTemplate`,
`RoutePreviewNavigationTemplate`, `PlaceListMapTemplate`.

`SearchTemplate` and `SignInTemplate` need text. A phone keyboard cannot appear on the car's
virtual display, so Gearslip draws its own and feeds the result back through the template's
listener.

### Content limits

Gearslip reports these limits through the constraint service, and expects you to honour them:

| Limit | Value |
| --- | --- |
| Grid items | 12 |
| Pane rows | 4 |
| Route list rows | 3 |
| Anything else | 12 |

Gearslip enables app-driven refresh, so you may invalidate your own template without the driver
touching the screen.

### Drawing a map

A navigation app draws its own pixels. Gearslip lends it a real `Surface` taken from a
`SurfaceView` inside the car's virtual display, so your map reaches the head unit through the
existing video pipe. Gearslip never reads those pixels back.

Gearslip then reports which part of that surface the driver can see. Treat the visible area as
the region safe for your own controls, and the stable area as the region that never gets
covered. Gearslip reports the same area for both, and it depends on the template:

| Template | What Gearslip covers |
| --- | --- |
| `NavigationTemplate` | A strip along the bottom for the trip estimate |
| `MapWithContentTemplate`, `MapTemplate`, and the place list and route preview templates | A content pane on the left |

The turn card and map buttons float over the visible area, as they do in Android Auto.

Your app receives four gestures, never raw touches: `onClick`, `onScroll`, `onFling` and
`onScale`. Coordinates are surface pixels. Scroll distances follow `GestureDetector`'s
convention, which reports the previous position minus the current one.

### Car hardware

A phone knows nothing about a car's sensors, so Gearslip answers every car-hardware request with
"unsupported". It answers rather than ignores: an app whose sensor manager waits for a reply
hangs when the host stays silent. Design for a host that reports no vehicle data at all.

Gearslip accepts the suggestions an app pushes and discards them, because no screen shows them
yet. Your app will not fail for offering them.

### Day and night

Gearslip decides whether it is dark outside and pushes that to your app as a configuration
change, with `UI_MODE_NIGHT_YES` or `UI_MODE_NIGHT_NO`. Take your map style from the
configuration the host reports. The configuration also carries the car's density, screen size and
orientation, and always sets `UI_MODE_TYPE_CAR`.

---

## Media apps

Gearslip draws the media UI itself: browsing, artwork, lyrics, transport controls and a
now-playing pill in the nav bar. Your app supplies a browse tree and a media session.

Gearslip lists a media app when it exports a `MediaBrowserService` **and** declares the Android
Auto meta-data. Both halves are required, because the service alone also appears on phone-only
players that expose a browser to Bluetooth and Wear:

```xml
<application>
    <meta-data
        android:name="com.google.android.gms.car.application"
        android:resource="@xml/automotive_app_desc" />
</application>
```

```xml
<!-- res/xml/automotive_app_desc.xml -->
<automotiveApp>
    <uses name="media" />
</automotiveApp>
```

Publish playback state, metadata, duration and artwork through your `MediaSession`, and support
play, pause, next, previous and seek. Gearslip reconnects to the app the driver used last and
can start it playing on connection.

---

## Messaging apps

Gearslip shows conversations from the notifications your app already posts, as Android Auto
does. Your app needs no car screen of its own.

Gearslip lists a messaging app when its car descriptor declares notifications:

```xml
<!-- res/xml/automotive_app_desc.xml -->
<automotiveApp>
    <uses name="notification" />
</automotiveApp>
```

Reference it from the same `com.google.android.gms.car.application` meta-data that a media app
uses. Opening your tile on the car screen shows your recent conversations.

### Posting messages

Post each conversation as a `MessagingStyle` notification. Gearslip reads the conversation
title, each message's sender and text, and whether the chat is a group, and shows the last eight
messages. Leave the sender empty on the phone owner's own messages; Gearslip treats a message
with no sender as one the driver sent.

Gearslip reads new messages aloud and lets the driver reply by voice or with a quick reply. For
that, attach two actions, either to the notification or to its `WearableExtender`:

- **Reply:** an action carrying a `RemoteInput` that allows free-form input. Gearslip fills it
  with the driver's reply and fires the action's intent.
- **Mark as read:** an action with `SEMANTIC_ACTION_MARK_AS_READ`. Gearslip shows a Mark read
  button when you provide it.

### Which notifications interrupt the driver

Gearslip pops up a card for a conversation, a notification with a reply action, or a
notification in the `msg`, `missed_call`, `alarm`, `reminder` or `event` category. A channel
below default importance never pops up. Every other notification waits quietly on the
dashboard.

Gearslip drops these altogether:

- ongoing notifications and group summaries;
- the `transport`, `progress`, `service`, `sys` and `navigation` categories; and
- media notifications, because the player already shows them.

---

## Gearslip apps

This section specifies protocol version 1. Gearslip does not yet implement it.

A Gearslip app describes a screen and lets Gearslip render it in Compose, using the car's theme,
the driver's chosen scale and the current day or night state. Your app draws nothing and receives
no surface. Everything the driver sees comes from the model below, which means your screen
matches the rest of the car UI and cannot be made unreadable.

Write a Gearslip app to reach the parts of the car UI the Car App Library does not cover. Write a
templated app if you need to draw a map, because version 1 lends no surface.

### Discovery

Export a service with the Gearslip action and declare the protocol version it speaks:

```xml
<service
    android:name=".GearslipService"
    android:exported="true">
    <intent-filter>
        <action android:name="app.seb3thehacker.gearslip.APP" />
    </intent-filter>
    <meta-data
        android:name="app.seb3thehacker.gearslip.version"
        android:value="1" />
</service>
```

Gearslip scans for these services once per car session and shows each one as a tile in the
launcher, under your application's own label and icon. To override either on the car screen, add
`app.seb3thehacker.gearslip.label` or `app.seb3thehacker.gearslip.icon`.

Gearslip binds to any app that declares this service. It asks the driver for nothing, exactly as
a head unit asks nothing before running an Android Auto app.

Gearslip binds your service when the driver opens your tile and unbinds it when they leave. Keep
no state that a rebind cannot rebuild.

### The interface

Copy these three files into `src/main/aidl/app/seb3thehacker/gearslip/api/`. A published artifact
will replace them.

```aidl
// IGearslipApp.aidl
package app.seb3thehacker.gearslip.api;

import app.seb3thehacker.gearslip.api.IGearslipHost;
import app.seb3thehacker.gearslip.api.IScreenCallback;

interface IGearslipApp {
    /** Return the highest version both sides speak. Gearslip passes the version it offers. */
    int negotiate(int hostVersion);

    void onCreate(IGearslipHost host);
    void onStart();
    void onStop();

    /** Describe the current screen. Answer through the callback, on any thread. */
    void getScreen(IScreenCallback callback);

    void onAction(String actionId);
    void onTextInput(String fieldId, String text);
}
```

```aidl
// IGearslipHost.aidl
package app.seb3thehacker.gearslip.api;

interface IGearslipHost {
    /** Tell Gearslip the screen changed. It calls getScreen again. */
    void invalidate();

    /** Open the car keyboard. The result arrives at onTextInput. */
    void requestTextInput(String fieldId, String prompt, String initialText);

    String getEnvironment();
    String getNowPlaying();

    /** One of play, pause, toggle, next, previous. */
    void mediaCommand(String command);

    /** Leave the app and return to the launcher. */
    void finish();
}
```

```aidl
// IScreenCallback.aidl
package app.seb3thehacker.gearslip.api;

interface IScreenCallback {
    void onScreen(String screenJson);
    void onError(String message);
}
```

### Versioning

Gearslip calls `negotiate` first, passing the version it offers. Return the highest version you
also speak. Gearslip uses that version for the rest of the connection and drops the app when the
answer exceeds what it offered. This mirrors how Gearslip negotiates car API levels with
templated apps, and it lets either side move first.

Version 1 is the only version. Reject anything you do not recognise by returning 1.

### Lifecycle

1. Gearslip binds your service and calls `negotiate`.
2. Gearslip calls `onCreate`, handing you the host interface. Keep it.
3. Gearslip calls `onStart`, then `getScreen`.
4. You answer through `onScreen`. Gearslip renders it.
5. The driver taps something. Gearslip calls `onAction` with that element's id.
6. Your screen changes, so you call `invalidate`. Gearslip calls `getScreen` again.
7. The driver leaves. Gearslip calls `onStop` and unbinds.

Every call arrives on a binder thread. Answer `getScreen` promptly; Gearslip shows the previous
screen until you do. Gearslip coalesces repeated `invalidate` calls.

### The screen model

Answer `getScreen` with a JSON object.

```json
{
  "protocol": 1,
  "type": "list",
  "title": "Stations",
  "items": [
    { "id": "r6", "title": "BBC Radio 6", "subtitle": "Music", "icon": "play" },
    { "id": "r4", "title": "BBC Radio 4", "subtitle": "Speech", "icon": "play" }
  ],
  "actions": [
    { "id": "refresh", "title": "Refresh", "icon": "refresh", "primary": true }
  ]
}
```

`type` takes one of four values:

| Type | Renders as | Uses |
| --- | --- | --- |
| `list` | A vertical list of rows | `items` |
| `grid` | A grid of tiles | `items` |
| `pane` | A short detail pane beside its actions | `items`, `actions` |
| `message` | A single message and its actions | `text`, `actions` |

An item takes `id`, `title`, and optionally `subtitle`, `icon` and `enabled`. Gearslip calls
`onAction` with the item's `id` when the driver taps it. An item with `"type": "input"` renders
as a text field carrying `value`; tapping it opens the car keyboard, and the result arrives at
`onTextInput` under the item's id.

An action takes `id`, `title`, and optionally `icon` and `primary`. Gearslip renders at most two
actions and emphasises the primary one.

`icon` names a built-in glyph: `play`, `pause`, `next`, `previous`, `search`, `settings`, `home`,
`apps`, `notifications`, `info`, `share`, `place`, `favorite`, `add`, `remove`, `refresh`,
`check`, `error`. Version 1 carries no images. Gearslip drops an unknown name and renders the
row without a glyph.

Gearslip truncates any list past the limits in [Content limits](#content-limits) and shortens
long strings. Send a screen that fits, rather than one Gearslip has to cut.

### Reading the car

`getEnvironment` returns the car's current state:

```json
{
  "protocol": 1,
  "darkOutside": true,
  "moving": false,
  "frameWidth": 800,
  "frameHeight": 480,
  "densityDpi": 160,
  "batteryPercent": 82,
  "charging": true,
  "vehicle": "Honda"
}
```

`darkOutside` reports whether Gearslip believes it is dark, from the light sensor or the clock.
`moving` reports whether the phone's GPS shows the car moving. Both change during a session, so
read them each time you build a screen rather than caching them.

Treat `moving` as a comfort signal, not a safety guarantee. It reads false whenever the fix is
stale or missing, which includes tunnels and car parks.

### Controlling media

`getNowPlaying` returns what the car is playing, whichever app owns it:

```json
{ "title": "Sleepwalk", "artist": "Santo & Johnny", "playing": true, "durationMs": 142000 }
```

`mediaCommand` sends one of `play`, `pause`, `toggle`, `next` or `previous` to that app. Both
work regardless of which app is playing, so use them to put transport controls on your own
screen. Ship audio through a media app instead of driving these.

### Text input

A phone keyboard cannot appear on the car's virtual display, so Gearslip draws its own. Call
`requestTextInput` with a field id, a prompt and any existing text. Gearslip opens the keyboard
and calls `onTextInput` as the driver types, so treat each call as the current contents rather
than a final answer.

### What version 1 leaves out

Version 1 lends no surface, so no Gearslip app draws its own pixels. It carries no images beyond
the built-in glyphs. It places apps only in the launcher; contributing a dashboard card or a
home-screen pane comes later. Anything that needs its own rendering belongs in a templated app.

---

## Works with Gearslip

Once your app runs well on Gearslip, say so in two places: your manifest and your README.

### In your manifest

Add these lines to `<application>`:

```xml
<meta-data
    android:name="app.seb3thehacker.gearslip.works_with"
    android:value="true" />
<meta-data
    android:name="app.seb3thehacker.gearslip.about"
    android:resource="@string/gearslip_about" />
```

Gearslip then marks your app "Works with Gearslip" in its Recommended apps list and on your app's
page in Gearslip. An installed app with the flag gets its own place at the top of the list.

The second entry is optional. It gives your page one sentence on what your app does in the car,
such as "Spots trackers and cameras nearby." Write it in place with `android:value` if you have no
string resource for it.

The flag is a label. Gearslip finds and runs your app the same way with or without it.

### In your README

Add the badge:

```markdown
[![Works with Gearslip](https://raw.githubusercontent.com/twinksoftware/gearslip/main/docs/badge/works-with-gearslip.svg)](https://github.com/twinksoftware/gearslip)
```

It matches the flat badges most READMEs already show.

---

## Further reading

- [README.md](../README.md) - what Gearslip is.
- [CONTRIBUTING.md](../CONTRIBUTING.md) - how to build Gearslip and report bugs.
- [Android for Cars App Library](https://developer.android.com/training/cars/apps) - the
  reference for templated apps.
