"""
Edge TTS Proxy Server
---------------------
Lightweight FastAPI server wrapping Microsoft Edge TTS.
Exposes HTTP endpoints for Android ebook reader app.
Protected by X-API-Key header authentication.

Usage:
    uvicorn main:app --host 0.0.0.0 --port 8080

Endpoints:
    GET  /health          → Health check (no auth) + voice count (with auth)
    GET  /voices          → List all available Edge TTS voices (auth required)
    POST /tts             → Synthesize text to speech (auth required, streaming audio/mpeg)
"""

import logging
import os
import time
import secrets

import edge_tts
from fastapi import FastAPI, HTTPException, Request, Depends
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse
from fastapi.security import HTTPBearer
from pydantic import BaseModel, Field

# ── Logging ──────────────────────────────────────────────────────────────────
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
)
log = logging.getLogger("edge-tts-server")

# ── Constants ────────────────────────────────────────────────────────────────
DEFAULT_VOICE = "vi-VN-HoaiMyNeural"
DEFAULT_RATE = "+0%"
DEFAULT_PITCH = "+0Hz"
MAX_TEXT_LENGTH = 3000
SERVER_VERSION = "1.1.0"

# ── API Key Auth ─────────────────────────────────────────────────────────────
# Default key — change via environment variable EDGE_TTS_API_KEY
API_KEY = os.getenv(
    "EDGE_TTS_API_KEY",
    "dCUHsBmDQJws88KGk_t1tl-fNGAORdOYdpkqPPNKGPI",
)

security = HTTPBearer(auto_error=False)


async def verify_api_key(request: Request, credentials=Depends(security)):
    """FastAPI dependency: require valid X-API-Key in header."""
    # Accept X-API-Key header OR Bearer token
    api_key = request.headers.get("x-api-key", "")
    if not api_key and credentials:
        api_key = credentials.credentials
    if not api_key or not secrets.compare_digest(api_key, API_KEY):
        raise HTTPException(status_code=401, detail="Invalid or missing API key. Use X-API-Key header.")
    return True


def has_api_key(request: Request) -> bool:
    """Check if request has a valid API key (without throwing)."""
    api_key = request.headers.get("x-api-key", "")
    if not api_key:
        auth_header = request.headers.get("authorization", "")
        if auth_header.startswith("Bearer "):
            api_key = auth_header[7:]
    return secrets.compare_digest(api_key, API_KEY)


# ── App Setup ────────────────────────────────────────────────────────────────
app = FastAPI(
    title="Edge TTS Proxy Server",
    version=SERVER_VERSION,
    description="Free, unlimited TTS proxy for Android ebook reader using Microsoft Edge TTS. Protected by API key.",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


# ── Request Models ───────────────────────────────────────────────────────────
class TTSRequest(BaseModel):
    text: str = Field(..., min_length=1, max_length=MAX_TEXT_LENGTH)
    voice: str = Field(default=DEFAULT_VOICE)
    rate: str = Field(default=DEFAULT_RATE, pattern=r"^[+-]\d+%$")
    pitch: str = Field(default=DEFAULT_PITCH, pattern=r"^[+-]\d+Hz$")


class TTSRequestShort(BaseModel):
    """For very short text (under 200 chars) - direct response."""
    text: str = Field(..., min_length=1, max_length=200)
    voice: str = Field(default=DEFAULT_VOICE)


# ── Cached Helpers ──────────────────────────────────────────────────────────
_voices_cache = None
_voices_cache_time = 0
VOICES_CACHE_TTL = 300  # 5 minutes


async def _get_voices():
    """Get full voice list (cached with TTL)."""
    global _voices_cache, _voices_cache_time
    now = time.time()
    if _voices_cache is None or (now - _voices_cache_time) > VOICES_CACHE_TTL:
        _voices_cache = await edge_tts.list_voices()
        _voices_cache_time = now
        log.info(f"Voices cache refreshed: {len(_voices_cache)} voices")
    return _voices_cache


# ── Endpoints ────────────────────────────────────────────────────────────────

@app.get("/health")
async def health(request: Request):
    """Health check. Basic status always available; voice count only with API key."""
    base = {
        "status": "ok",
        "version": SERVER_VERSION,
        "auth_required": True,
    }
    if has_api_key(request):
        try:
            voices = await _get_voices()
            base["voice_count"] = len(voices)
            base["default_voice"] = DEFAULT_VOICE
            base["authenticated"] = True
        except Exception as e:
            log.error(f"Health check failed: {e}")
            base["voice_count_error"] = str(e)
    return base


@app.get("/voices")
async def list_voices(_: bool = Depends(verify_api_key)):
    """List all available Edge TTS voices with metadata. (Auth required)"""
    try:
        voices = await _get_voices()
        result = []
        for v in voices:
            result.append({
                "name": v["Name"],
                "short_name": v["ShortName"],
                "locale": v["Locale"],
                "gender": v["Gender"],
                "friendly_name": v.get("FriendlyName", v["ShortName"]),
            })
        return {"voices": result}
    except Exception as e:
        log.error(f"Failed to list voices: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/tts")
async def text_to_speech(request: TTSRequest, _: bool = Depends(verify_api_key)):
    """
    Synthesize text to speech using Edge TTS. (Auth required)
    Returns streaming audio/mpeg response.
    """
    text = request.text.strip()
    voice = request.voice or DEFAULT_VOICE
    rate = request.rate or DEFAULT_RATE
    pitch = request.pitch or DEFAULT_PITCH

    if not text:
        raise HTTPException(status_code=400, detail="Text is required")

    if len(text) > MAX_TEXT_LENGTH:
        log.warning(f"Text truncated from {len(text)} to {MAX_TEXT_LENGTH} chars")
        text = text[:MAX_TEXT_LENGTH]

    log.info(f"TTS request: voice={voice}, rate={rate}, pitch={pitch}, text_len={len(text)}")
    start_time = time.time()

    try:
        communicate = edge_tts.Communicate(
            text,
            voice,
            rate=rate,
            pitch=pitch,
        )

        async def generate():
            total_audio_bytes = 0
            try:
                async for chunk in communicate.stream():
                    if chunk["type"] == "audio":
                        total_audio_bytes += len(chunk["data"])
                        yield chunk["data"]
            finally:
                elapsed = time.time() - start_time
                log.info(
                    f"TTS complete: {total_audio_bytes} bytes in {elapsed:.2f}s "
                    f"({total_audio_bytes/1024/elapsed:.1f} KB/s)"
                )

        return StreamingResponse(
            generate(),
            media_type="audio/mpeg",
            headers={
                "X-TTS-Voice": voice,
                "X-TTS-Text-Length": str(len(text)),
            },
        )

    except Exception as e:
        elapsed = time.time() - start_time
        log.error(f"TTS failed after {elapsed:.2f}s: {e}")
        raise HTTPException(status_code=500, detail=f"TTS synthesis failed: {str(e)}")


@app.post("/tts/short")
async def text_to_speech_short(request: TTSRequestShort, _: bool = Depends(verify_api_key)):
    """
    Optimized endpoint for short text (under 200 chars). (Auth required)
    Returns complete audio MP3 bytes (non-streaming).
    """
    text = request.text.strip()
    voice = request.voice or DEFAULT_VOICE

    if not text:
        raise HTTPException(status_code=400, detail="Text is required")

    if len(text) > 200:
        raise HTTPException(status_code=400, detail="Text too long for short endpoint (max 200 chars)")

    log.info(f"Short TTS: voice={voice}, text='{text[:50]}...'")
    start_time = time.time()

    try:
        communicate = edge_tts.Communicate(text, voice)
        audio_data = bytearray()

        async for chunk in communicate.stream():
            if chunk["type"] == "audio":
                audio_data.extend(chunk["data"])

        elapsed = time.time() - start_time
        log.info(f"Short TTS done: {len(audio_data)} bytes in {elapsed:.2f}s")

        from fastapi.responses import Response
        return Response(
            content=bytes(audio_data),
            media_type="audio/mpeg",
            headers={
                "X-TTS-Voice": voice,
                "X-TTS-Elapsed": f"{elapsed:.2f}s",
            },
        )

    except Exception as e:
        elapsed = time.time() - start_time
        log.error(f"Short TTS failed after {elapsed:.2f}s: {e}")
        raise HTTPException(status_code=500, detail=str(e))


# ── Main ─────────────────────────────────────────────────────────────────────
if __name__ == "__main__":
    import uvicorn
    print(f"\U0001f680 Edge TTS Proxy Server v{SERVER_VERSION}")
    print(f"   Default voice: {DEFAULT_VOICE}")
    print(f"   Max text length: {MAX_TEXT_LENGTH} chars")
    print(f"   API Key auth: ENABLED (set EDGE_TTS_API_KEY env var to change)")
    print()
    uvicorn.run(app, host="0.0.0.0", port=8080)
