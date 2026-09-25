# Melody

An ad-free Android player for music on [Audius](https://docs.audius.co/). This independent app is not affiliated with Audius or Spotify.

## Features

- Search ranks exact song titles first. Telugu discovery is the default; choose Hindi, English or All as needed. Catalog availability depends on Audius.
- Favorites and custom playlists are stored on the phone without sign-in.
- Songs that artists make publicly downloadable can be saved for offline playback inside the app. Long press an offline song to delete it.
- Bass boost and equalizer presets use Android audio effects and depend on device support.
- No advertisements, tracking SDKs or storage permissions. Internet traffic is HTTPS only.

Audio quality depends on the source recording and Audius stream; sound controls do not increase the recording bitrate. Playback currently works while the app is open.

## Get the APK

Open **Actions → Build Android APK → latest successful run → Artifacts → Melody-APK**, unzip, and install `app-debug.apk` on Android 8.0 or newer. This personal test build is debug signed, not a Play Store release. Each GitHub runner may use a different debug key, so an update from a previous build may require uninstalling the previous APK first. Uninstalling deletes local favorites, playlists and offline songs. A stable release signing key stored privately is needed for reliable in-place updates.

## Build locally

Install JDK 17, Android SDK platform 35 and build tools 35.0.0, and Gradle 8.9. Run `gradle assembleDebug lintDebug` in the project root.
