# Edge TTS Proxy Server

Lightweight Python server wrapping Microsoft Edge TTS for the ebook reader Android app.

## Features

- **Free & unlimited** — uses Microsoft Edge TTS (no API key, no costs)
- **Vietnamese voices** — Hoài My (female), Nam Minh (male)
- **Streaming audio** — real-time audio streaming, no buffering
- **CORS enabled** — access from any mobile/desktop client
- **Lightweight** — single Python file, FastAPI + edge-tts

## Quick Start

```bash
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8765
```

## API Endpoints

### `GET /health`
Health check:
```json
{"status": "ok", "version": "1.0.0", "voice_count": 802, "default_voice": "vi-VN-HoaiMyNeural"}
```

### `GET /voices`
List all available voices:
```json
{"voices": [{"name": "...", "short_name": "...", "locale": "vi-VN", "gender": "Female", ...}]}
```

### `POST /tts`
Synthesize text → audio (streaming):

```json
{
  "text": "Xin chào, đây là giọng đọc từ Edge TTS.",
  "voice": "vi-VN-HoaiMyNeural",
  "rate": "+0%",
  "pitch": "+0Hz"
}
```

Response: `audio/mpeg` stream.

### `POST /tts/short`
For text under 200 chars — returns complete MP3 bytes.

## Testing

```bash
# Health check
curl http://localhost:8765/health

# List voices
curl http://localhost:8765/voices | jq '.voices[] | select(.locale | startswith("vi"))'

# Synthesize
curl -X POST http://localhost:8765/tts \
  -H "Content-Type: application/json" \
  -d '{"text":"Xin chào","voice":"vi-VN-HoaiMyNeural"}' \
  -o output.mp3
```

## Configuration

| Env Var | Default | Description |
|---------|---------|-------------|
| `HOST` | `0.0.0.0` | Bind address |
| `PORT` | `8765` | Server port |
| `MAX_TEXT_LENGTH` | `3000` | Max chars per request |

## Android Integration

From the Android app, configure the server URL in Settings (e.g. `http://192.168.1.100:8765`).
