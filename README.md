# Ebook Reader Android

Native Android ebook reader with Edge TTS voice reading.

## Components

- **edge-tts-server/** — Python proxy server wrapping Microsoft Edge TTS
- **android/** — Android app (Kotlin + Jetpack Compose)

## Requirements

- Android Studio / Android SDK
- Python 3.10+ (for TTS server)
- Edge TTS (free, unlimited)

## Getting Started

### TTS Server
```
cd edge-tts-server
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8765
```

### Android App
Open `android/` in Android Studio or build with:
```
cd android
./gradlew assembleDebug
```

