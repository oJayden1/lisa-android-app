# LiSA Android

Native Android (**Kotlin + Jetpack Compose + Material 3**) port of [Cosmicoppai/LiSA](https://github.com/Cosmicoppai/LiSA) — stream and download Anime & Manga on Android without requiring an external Python or Electron backend.

## Features
- **Anime & Manga Mode Switch**: Dedicated search, explore, watchlist/readlist, and details flows.
- **Built-in Media3 ExoPlayer**:
  - Multi-quality adaptive HLS playback (`360p` / `720p` / `1080p` / `Auto`)
  - Sub / Dub (`jpn` / `eng`) audio switching preserving playback position
  - Playback speed control, landscape fullscreen mode, Picture-in-Picture (PiP), auto-next-episode, and External Player launch (VLC, MX Player, MPV)
- **Built-in Manga Reader**:
  - Vertical Webtoon & Horizontal Paged reading modes
  - Pinch-to-zoom, double-tap zoom, chapter drawer with asc/desc sort
  - Offline reader supporting both JPG chapter folders and native PDF rendering (`PdfRenderer`)
- **Background Download Manager & Library**:
  - `WorkManager` foreground service downloads with pause, resume, cancel, and batch scheduling
  - AES-128-CBC HLS segment decryption + lossless `.mp4` remuxing via Android `MediaExtractor` & `MediaMuxer`
  - Manga downloads in `JPG` or `PDF` (`PdfDocument`)
- **Cloudflare / DDoS-Guard Session Resolver**: Automatic in-app `WebView` cookie clearance with 24-hour Room cache and interactive fallback dialog when needed.

## Tech Stack
- Kotlin 2.0, Jetpack Compose (Material 3), Navigation Compose
- Hilt (DI + WorkManager), Room (SQLite), DataStore Preferences
- AndroidX Media3 (ExoPlayer + HLS + OkHttpDataSource)
- OkHttp 4, Jsoup, Kotlinx Serialization, Coil

## Build
```bash
./gradlew testDebugUnitTest assembleDebug
```
The compiled APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.
