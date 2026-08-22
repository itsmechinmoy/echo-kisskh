# Echo KissKH Extension

An extension for [Echo](https://github.com/brahmkshatriya/echo) to stream Asian dramas, anime, and movies from [KissKH](https://kisskh.ovh).

## Features

- **Home Feed**: Browse **Popular** and **Latest Updates** feeds with pagination.
- **Search & Quick Search**: Search dramas, movies, and anime by keyword or title with instant suggestions.
- **Drama Details**: View posters, synopses, airing status, countries, types, episode counts, and trailer links.
- **Episode Listings**: Complete episode lists with multi-server playback support.
- **Direct Video Streaming**: HLS/Progressive video stream resolution with customized origin and referer header support.
- **Subtitles with Automatic Decryption**: Multi-language subtitle tracks with on-the-fly AES-CBC decryption for KissKH `.txt` subtitle files.
- **Share**: Direct link sharing for dramas and specific episode playback.
- **Multi-Domain Settings**: Configurable fallback domains (`kisskh.ovh`, `kisskh.do`, `kisskh.co`, `kisskh.id`, `kisskh.la`).

## Settings

- **Preferred Domain**: Select your preferred KissKH domain (`kisskh.ovh` by default).

## Development & Testing

### Local Testing
Run unit tests:
```bash
./gradlew ext:test
```

### Build Extension JAR & Android APK
```bash
./gradlew ext:shadowJar app:assembleDebug
```

## Author

- **itsmechinmoy** ([GitHub](https://github.com/itsmechinmoy))
