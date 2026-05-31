#!/usr/bin/env bash
# Start Edge TTS server
cd /home/ngtri/projects/ebook-reader-android/edge-tts-server
exec python -m uvicorn main:app --host 0.0.0.0 --port 8080
