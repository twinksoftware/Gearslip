# Contributing to Gearslip

Thanks for helping. Bug reports from real cars are the most useful thing you can send, so
start there if you aren't sure where to begin.

## Reporting a bug

Open an issue on [GitHub](https://github.com/twinksoftware/gearslip/issues) and include:

- your car's make, model, and year, and whether the head unit is the original one; and
- the log from the drive. Open Logs on the phone app's home screen and tap Share log.

The log records your phone, its Android version, the Gearslip version, the head unit, and
why the connection ended. Most car bugs can't be fixed without it. Head units often report
only a part number, so the log can't name your car for you.

## Building

You need Android Studio, or the Android SDK with JDK 21. Gradle downloads the NDK, CMake,
and the speech library on the first build.

```
./gradlew :gearslip:assembleDebug
```

The APK lands at `build/Gearslip-debug.apk`. It installs as Gearslip Dev, next to the
regular app.

## Pull requests

Work happens on the `experimental` branch, which merges into `main` at each release. Base your
branch on `experimental`, and open your pull request against it.

- Keep each pull request to one change, and explain the problem it solves.
- Say how you tested it: which car or head unit, which phone, and which apps.
- Code written with AI tools is welcome. Read it, test it, and stand behind it as you would
  your own.
- Gearslip asks for no adb, root, or Shizuku. A feature must work with ordinary Android
  permissions and on-device consent dialogs.

## Translating

Translations happen on [Weblate](https://hosted.weblate.org/engage/gearslip/), never in pull
requests. Pick your language, or start a new one, and translate in the browser. Weblate
sends the finished work to this repository as a pull request.

Don't edit the translated `values-*/strings.xml` files by hand. Weblate keeps its own copy of
every language, so a change made in git collides with Weblate's next update, and the pull
request that made it gets closed. If a code change adds or rewords text, change only the
English file, `values/strings.xml`; Weblate picks up the new strings for translators.

Keep the tone of the English: short, plain, and friendly. Leave placeholders such as `%1$s`
and `%1$d` in place; Gearslip fills them with names and numbers. Untranslated strings show
in English, so a partial translation is still useful.

Before your first translation, Weblate asks you to accept the
[Contributor License Agreement](CLA.md). That counts as signing it.

## Writing

Text that users read, such as screens, release notes, and docs, follows Strunk's
[*The Elements of Style*](https://www.gutenberg.org/ebooks/37134). In short: use the active
voice, state things positively, cut needless words, and use the serial comma.

## License

Gearslip is licensed under AGPL-3.0, and your contributions are too. Twink Software LLC, which
owns Gearslip, also sells a commercial license, described in [NOTICE.md](NOTICE.md).

Before we can merge your first pull request, you sign the
[Contributor License Agreement](CLA.md). You keep the copyright in your work, and it stays
available as free software; the agreement lets us offer it under the commercial license too.
A bot comments on your pull request with a link, and signing takes one click.
