# Melody

A small, ad-free Android music player. Browse trending tracks, search the Audius catalog, play and seek tracks, and save favorites locally. Requires an internet connection; available tracks depend on Audius. Some tracks may restrict streaming. This app adds no advertisements.

## Get the APK

Open **Actions → Build Android APK → latest successful run → Artifacts → Melody-APK**. Download and unzip the artifact, then install `app-debug.apk` on an Android 8.0 or newer phone. Android may ask you to allow installation from the app used to open the file. This is a debug-signed personal test build; it is not a Play Store release.

## Build locally

Install JDK 17, Android SDK platform 35 and build tools 35.0.0, and Gradle 8.9. Run `gradle assembleDebug` in the project root.

## Details

- Online catalog: [Audius](https://docs.audius.co/); this is an independent app, not affiliated with Audius or Spotify.
- Favorites are stored only on your phone. Clearing the app's data removes them.
- Playback works while the app is open. Background service, downloads, accounts, and playlists are outside this first version.
- A song can fail when Audius restricts access, its host is temporarily unavailable, or the network is offline. Skip to another track in that case.
