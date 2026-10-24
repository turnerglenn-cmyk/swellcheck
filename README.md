# SwellCheck

A private surf and sea-kayak forecast for Android, with a home-screen widget.

- Surf rating (Flat → Epic) from swell height, period, direction and wind
- Paddling call (Go / Marginal / No go) against your own limits, with an offshore-wind warning
- Wind, gusts, swell, waves, sea temperature and approximate tide times
- 3-day outlook with night and day shaded from the real sunrise and sunset, plus a home-screen widget
- "Best time to go" for the next three days, with the reason in plain English
- 12 colour themes (two light ones for bright sun, a red one for night vision) and 4 layouts: Classic, Verdict first, Hour by hour, Tiles
- No ads, no analytics, no account, no Google services. Location is rounded to ~1 km and only sent to Open-Meteo.

If SwellCheck is useful to you, you can [buy me a coffee on Liberapay](https://liberapay.com/Glenn_Turner/)
(there's a button for it at the bottom of Settings).

Licence: GPL-3.0-or-later. Data: [Open-Meteo](https://open-meteo.com/), CC BY 4.0.

## Install

Download the latest `app-release.apk` from
[Releases](https://github.com/turnerglenn-cmyk/swellcheck/releases) and open it on your phone
(allow "install unknown apps" for your browser or file manager when asked).
IzzyOnDroid and F-Droid listings are on the way.

**Automatic updates with [Obtainium](https://obtainium.imranr.dev/):** tap *Add app*, paste
`https://github.com/turnerglenn-cmyk/swellcheck` and Obtainium will install each new release
straight from GitHub.

Then long-press your home screen → Widgets → SwellCheck to add the widget.

## Building it yourself (Linux Mint)

1. Install Android Studio: download the Linux `.tar.gz` from developer.android.com/studio,
   then `sudo tar -xzf ~/Downloads/android-studio-*-linux.tar.gz -C /opt` and run
   `/opt/android-studio/bin/studio.sh`. (Mint blocks Snap by default, so skip the snap package.)
   Gradle needs JDK 21; Android Studio's bundled JDK is fine.
   For USB debugging also run `sudo apt install android-sdk-platform-tools-common`
   (it adds the phone permissions Linux needs).
2. **File → Open** and pick this `swellcheck` folder. Let Gradle sync (the first time takes a while).
3. Plug in your phone with USB debugging on (Settings → About → tap *Build number* 7 times →
   Developer options → USB debugging), choose it in the device list and press **Run ▶**.
   If the phone doesn't show up: `adb kill-server && adb devices` and accept the prompt on the phone.

From a terminal instead: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.

The code uses no third-party libraries: just Android itself and Kotlin.

## Releasing (maintainer notes)

### One-time setup

- **Signing key.** Created once via Android Studio (**Build → Generate Signed App Bundle / APK →
  APK → Create new…**). It lives *outside* this repo (e.g. `~/keys/swellcheck-release.jks`,
  alias `swellcheck`). Back up the `.jks` file and both passwords somewhere safe: without them
  you can never publish an update, only a new app. `*.jks` is in `.gitignore`.
- **Git and GitHub CLI.** `sudo apt install git gh`, then
  `git config --global user.name "Glenn Turner"`, `git config --global user.email <you>`
  and `gh auth login` (GitHub.com → HTTPS → log in with a web browser).

### Every release

1. **Bump the version** in `app/build.gradle.kts`: `versionCode` +1 and a new `versionName`
   (e.g. 3 / 0.2.1 → 4 / 0.2.2). Update the same two numbers, the `commit: v…` tag and
   `CurrentVersion`/`CurrentVersionCode` in `fdroid-metadata-draft.yml`.
2. **Write the changelog**: `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`,
   one or two plain lines (under 500 characters).
3. **Build the signed APK**: **Build → Generate Signed App Bundle / APK → APK** (not *App Bundle*:
   an `.aab` is only for Google Play) → **Choose existing…** keystore → passwords → **release** →
   **Create**. Result: `app/release/app-release.apk`.
4. **Test it on a phone** (`-r` keeps your settings):
   ```
   adb install -r app/release/app-release.apk
   ```
   Check the main screen, both charts, Settings and the widget. Release builds are shrunk by R8,
   so this matters. (A debug build is signed with a different key: uninstall it first if
   `adb` complains about signatures.)
5. **Commit, tag, push and publish the release**:
   ```
   git add -A && git commit -m "SwellCheck 0.2.2"
   git push
   git tag v0.2.2 && git push --tags
   gh release create v0.2.2 app/release/app-release.apk \
     --title "SwellCheck 0.2.2" \
     --notes-file fastlane/metadata/android/en-US/changelogs/4.txt
   ```
6. IzzyOnDroid and F-Droid pick up the new tag automatically once the app is listed there.

### Getting listed (first time only)

- **IzzyOnDroid** (fast, usually days): once a GitHub release with an APK exists, open a
  request at https://gitlab.com/IzzyOnDroid/repo/-/issues with the repo link.
- **F-Droid** (slower, most trusted; F-Droid builds the app from source itself): open a
  "Request For Packaging" at https://gitlab.com/fdroid/rfp/-/issues, or fork
  https://gitlab.com/fdroid/fdroiddata and add `metadata/io.github.swellcheck.yml`
  based on `fdroid-metadata-draft.yml`. Store text and changelogs come from `fastlane/`.

### Settings that matter for publishing

- Donations: the "Buy me a coffee" button in Settings → About opens
  https://liberapay.com/Glenn_Turner/ (`donate_url` in `app/src/main/res/values/strings.xml`).
  The "Source code" button uses `source_url` in the same file.
- The app ID `io.github.swellcheck` can never change after the first public release.
- Open-Meteo's free API is for non-commercial use, which covers a free, ad-free open-source app.
  Donations were raised with them by email (info@open-meteo.com); keep their reply on file.
- `dependenciesInfo` is switched off in `app/build.gradle.kts` because F-Droid rejects
  Google's encrypted dependency blob. Leave it off.

## How the ratings work

**Surf.** Swell height sets the base score (0.6–2.5 m scores best), longer periods add to it and
very short periods take away. If you set which way the beach faces, swell more than 60° off the
beach's direction loses points, offshore wind up to 20 kn adds points, and onshore or strong
cross-shore wind takes them away. See `Rating.kt`; the rules are deliberately simple and easy to tweak.

**Paddling.** "No go" if wind, gusts or wave height go over your limits, or if there's an offshore
wind above 10 kn. "Marginal" within 75% of a limit or with an offshore breeze above 6 kn.

**Tides** are turning points of Open-Meteo's modelled sea level (~8 km grid). They are indicative
only. Open-Meteo itself says they're not for coastal navigation.
