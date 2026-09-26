# Melody

An ad-free Android player for music on [Audius](https://docs.audius.co/). This independent app is not affiliated with Audius or Spotify.

## Features

- Search ranks exact song titles first. It also checks artist supplied album, description and tags where available. Telugu discovery is the default; choose Hindi, English or All as needed. Catalog availability depends on Audius.
- Discover opens with popular matches and recent uploads for the selected language, a shelf for artist-enabled downloads, and a refresh control. The All language shows Audius weekly trending and latest uploads. These are Audius uploads, not a chart of all Telugu movie releases.
- The **On phone** tab imports audio files you own through Android's file picker without requesting broad storage access. Local song search also checks embedded album, composer and author tags where present. This is a practical way to play older Telugu songs already on your device.
- Tap the small player or **Expand** to view artwork and a larger player. Playback advances through the current song list. It includes previous, next, 10-second seeking and repeat controls.
- The expanded player has **Shuffle**. It shuffles upcoming songs while keeping the current song playing. The **Recent** tab lists up to 50 songs actually played, stored on the phone; long press to remove an entry.
- Favorites and custom playlists are stored on the phone without sign-in.
- A download button is visible beside each song. Audius downloads work only when the artist enables public downloads. Long press an offline song to delete it.
- Bass boost and equalizer presets use Android audio effects and depend on device support.
- No advertisements, tracking SDKs or storage permissions. Internet traffic is HTTPS only.

Audio quality depends on the source recording and Audius stream; sound controls do not increase the recording bitrate. Playback currently works while the app is open.

**Catalog limitation:** Melody cannot legally promise every commercial Telugu film recording, old or new. Adding a complete catalog requires a licensed provider and its playback rights. Searching by film, actor, singer or lyric writer works only if the source provides that metadata and the song itself is available to play.

Queue shuffle and on-device listening history were inspired by [Echo Music](https://github.com/EchoMusicApp/Echo-Music). This implementation is independent and does not include Echo's YouTube Music streaming or download integration.

## Get the APK

Open **Actions → Build Android APK → latest successful run → Artifacts → Melody-APK**, unzip, and install `app-debug.apk` on Android 8.0 or newer. This personal test build is debug signed, not a Play Store release. Each GitHub runner may use a different debug key, so an update from a previous build may require uninstalling the previous APK first. Uninstalling deletes local favorites, playlists and offline songs. A stable release signing key stored privately is needed for reliable in-place updates.

## Build locally

Install JDK 17, Android SDK platform 35 and build tools 35.0.0, and Gradle 8.9. Run `gradle assembleDebug lintDebug` in the project root.
