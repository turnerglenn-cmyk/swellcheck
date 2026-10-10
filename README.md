# SwellCheck

A private surf and sea-kayak forecast for Android, with a home-screen widget.

- Surf rating (Flat → Epic) from swell height, period, direction and wind
- Paddling call (Go / Marginal / No go) against your own limits, with an offshore-wind warning
- Wind, gusts, swell, waves, sea temperature and approximate tide times
- 3-day outlook, plus a widget showing now and the next 12 hours
- No ads, no analytics, no account, no Google services. Location is rounded to ~1 km and only sent to Open-Meteo.

Licence: GPL-3.0-or-later. Data: [Open-Meteo](https://open-meteo.com/), CC BY 4.0.

## Building it (Linux Mint)

1. Install Android Studio: download the Linux `.tar.gz` from developer.android.com/studio,
   then `sudo tar -xzf ~/Downloads/android-studio-*-linux.tar.gz -C /opt` and run
   `/opt/android-studio/bin/studio.sh`. (Mint blocks Snap by default, so skip the snap package.)
   The setup wizard installs the Android SDK. For USB debugging also run
   `sudo apt install android-sdk-platform-tools-common` (it adds the phone permissions Linux needs).
2. **File → Open** and pick this `swellcheck` folder. Let Gradle sync (the first time takes a while).
3. Plug in your phone with USB debugging on (Settings → About → tap *Build number* 7 times →
   Developer options → USB debugging), choose it in the device list and press **Run ▶**.
4. Long-press your home screen → Widgets → SwellCheck to add the widget.

From a terminal instead: `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.

The code uses no third-party libraries: just Android itself and Kotlin.

## Before you publish

- Put your donation link in `app/src/main/res/values/strings.xml` (`donate_url`).
  Liberapay suits F-Droid users best; Ko-fi or GitHub Sponsors also work.
- Put your repository link in `source_url` in the same file.
- Consider changing the app ID `io.github.swellcheck` (in `app/build.gradle.kts` and the
  `namespace`) to `io.github.<your-github-name>.swellcheck`. Do it **before** the first release;
  it can't change afterwards.
- Open-Meteo's free API is for non-commercial use, which covers a free, ad-free open-source app.
  Their terms don't mention donations, so a one-line email to info@open-meteo.com to confirm is
  worth sending.

## Publishing

### 1. Put the code on GitHub or Codeberg
Create a public repository, push this folder, then tag the release:

```
git init && git add . && git commit -m "SwellCheck 0.1.0"
git remote add origin https://github.com/YOUR_NAME/swellcheck.git
git push -u origin main
git tag v0.1.0 && git push --tags
```

### 2. IzzyOnDroid (fast, days rather than weeks)
Build a signed release APK in Android Studio (**Build → Generate Signed App Bundle / APK → APK**;
keep the keystore file and passwords safe, you'll need them for every future update), attach it to
a GitHub release for the tag, then request inclusion at
https://gitlab.com/IzzyOnDroid/repo/-/issues.

### 3. F-Droid main repository (slower, the most trusted)
Either open a "Request For Packaging" at https://gitlab.com/fdroid/rfp/-/issues, or fork
https://gitlab.com/fdroid/fdroiddata and add `metadata/io.github.swellcheck.yml`
(a starting point is in `fdroid-metadata-draft.yml`). F-Droid builds the app from your source
itself; the descriptions and changelog come from the `fastlane/` folder in this repo.

### 4. Releasing an update
Bump `versionCode` (+1) and `versionName` in `app/build.gradle.kts`, add
`fastlane/metadata/android/en-GB/changelogs/<versionCode>.txt`, commit, tag, push.

## How the ratings work

**Surf.** Swell height sets the base score (0.6–2.5 m scores best), longer periods add to it and
very short periods take away. If you set which way the beach faces, swell more than 60° off the
beach's direction loses points, offshore wind up to 20 kn adds points, and onshore or strong
cross-shore wind takes them away. See `Rating.kt`; the rules are deliberately simple and easy to tweak.

**Paddling.** "No go" if wind, gusts or wave height go over your limits, or if there's an offshore
wind above 10 kn. "Marginal" within 75% of a limit or with an offshore breeze above 6 kn.

**Tides** are turning points of Open-Meteo's modelled sea level (~8 km grid). They are indicative
only. Open-Meteo itself says they're not for coastal navigation.
