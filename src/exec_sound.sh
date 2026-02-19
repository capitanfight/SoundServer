#!/bin/bash
# exec_sound.sh — Start (or restart) the SoundServer.
# Checks the git repo for updates, recompiles if needed, then runs as a background daemon.

set -euo pipefail

DIR="$HOME/.soundserver"
REPO_DIR="$DIR/repo"         # where the git repo lives
BIN_DIR="$DIR/bin"           # where compiled .class files go
LOG="$DIR/soundserver.log"
PID_FILE="$DIR/soundserver.pid"

# ---------- helpers ----------------------------------------------------------

log() { echo "[soundserver] $*"; }

kill_existing() {
    if [[ -f "$PID_FILE" ]]; then
        local pid
        pid=$(cat "$PID_FILE")
        if kill -0 "$pid" 2>/dev/null; then
            log "Stopping existing server (PID $pid)..."
            kill "$pid" && sleep 1
        fi
        rm -f "$PID_FILE"
    fi
}

# ---------- update check -----------------------------------------------------

cd "$REPO_DIR"

log "Checking for updates in git repo..."

# Fetch without merging; suppress non-fatal errors (e.g. no network)
git fetch origin 2>/dev/null || true

LOCAL=$(git rev-parse HEAD)
REMOTE=$(git rev-parse @{u} 2>/dev/null || echo "$LOCAL")

if [[ "$LOCAL" != "$REMOTE" ]]; then
    log "Update found — pulling latest changes..."
    git pull --ff-only origin 2>/dev/null || {
        log "WARNING: git pull failed, running with existing version."
    }
    RECOMPILE=true
else
    log "Already up to date."
    RECOMPILE=false
fi

# ---------- compile if needed ------------------------------------------------

mkdir -p "$BIN_DIR"

SERVER_SRC="$REPO_DIR/SoundServer.java"
SERVER_CLASS="$BIN_DIR/SoundServer.class"

# Force recompile if source is newer than class file, or flagged by git pull
if [[ "$RECOMPILE" == true ]] || [[ ! -f "$SERVER_CLASS" ]] || [[ "$SERVER_SRC" -nt "$SERVER_CLASS" ]]; then
    log "Compiling SoundServer.java..."
    javac -d "$BIN_DIR" "$SERVER_SRC" && log "Compilation successful." || {
        log "ERROR: Compilation failed. Aborting."
        exit 1
    }
else
    log "No recompilation needed."
fi

# ---------- start daemon ------------------------------------------------------

kill_existing

log "Starting SoundServer in background..."
nohup java -cp "$BIN_DIR" SoundServer >> "$LOG" 2>&1 &
echo $! > "$PID_FILE"
log "SoundServer started (PID $(cat "$PID_FILE")). Log: $LOG"