#!/usr/bin/env bash
# Watchdog: restart Edge TTS server if dead
SERVER_PORT=8080
SERVER_DIR="$HOME/projects/ebook-reader-android/edge-tts-server"

if ! curl -sf http://localhost:$SERVER_PORT/health > /dev/null 2>&1; then
    cd "$SERVER_DIR" && python -m uvicorn main:app --host 0.0.0.0 --port $SERVER_PORT &
    echo "[$(date)] Edge TTS server restarted"
fi
