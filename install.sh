#!/bin/bash
# install.sh — First-time installation of SoundServer (no sudo required).
#
# What this does:
#   1. Clones/copies source files into ~/.soundserver/repo/
#   2. Creates ~/.soundserver/sounds/ for audio files
#   3. Compiles the server
#   4. Installs exec_sound.sh into ~/.local/bin/ (on PATH for the user)
#   5. Sets up autostart via:
#        • XDG autostart (~/.config/autostart/) — for GNOME/KDE/XFCE etc.
#        • systemd user service                 — reliable on all modern distros
#
# Usage:
#   bash install.sh [--repo <git-url>]
#
#   If --repo is given, the installer will clone that URL.
#   Otherwise it copies the files from the directory where install.sh lives.

set -euo pipefail

# ---------- parse args -------------------------------------------------------

REPO_URL=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --repo) REPO_URL="$2"; shift 2 ;;
        *) echo "Unknown argument: $1"; exit 1 ;;
    esac
done

# ---------- paths -------------------------------------------------------------

INSTALL_DIR="$HOME/.soundserver"
REPO_DIR="$INSTALL_DIR/repo"
BIN_DIR="$INSTALL_DIR/bin"
SOUNDS_DIR="$INSTALL_DIR/sounds"
LOG_DIR="$INSTALL_DIR/logs"
EXEC_DEST="$HOME/.local/bin/soundserver"
AUTOSTART_DIR="$HOME/.config/autostart"
SYSTEMD_DIR="$HOME/.config/systemd/user"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ---------- helpers -----------------------------------------------------------

info()    { echo -e "\e[32m[install]\e[0m $*"; }
warn()    { echo -e "\e[33m[install]\e[0m WARNING: $*"; }
error()   { echo -e "\e[31m[install]\e[0m ERROR: $*" >&2; exit 1; }
require() { command -v "$1" &>/dev/null || error "$1 is required but not found. Please install it first."; }

# ---------- pre-flight checks ------------------------------------------------

info "Checking requirements..."
require java
require javac
require git

JAVA_VERSION=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d. -f1)
[[ "$JAVA_VERSION" -ge 11 ]] 2>/dev/null || warn "Java 11+ recommended (found version ${JAVA_VERSION:-unknown})."

info "All requirements met."

# ---------- create directory structure ----------------------------------------

info "Creating directory structure..."
mkdir -p "$REPO_DIR" "$BIN_DIR" "$SOUNDS_DIR" "$LOG_DIR"
mkdir -p "$HOME/.local/bin" "$AUTOSTART_DIR" "$SYSTEMD_DIR"

# ---------- install source files ----------------------------------------------

if [[ -n "$REPO_URL" ]]; then
    info "Cloning repository from $REPO_URL ..."
    if [[ -d "$REPO_DIR/.git" ]]; then
        info "Repo already cloned — pulling latest..."
        git -C "$REPO_DIR" pull --ff-only
    else
        git clone "$REPO_URL" "$REPO_DIR"
    fi
else
    info "Copying source files from $SCRIPT_DIR ..."
    # Copy Java sources and scripts — skip the install script itself
    for f in SoundServer.java SoundClient.java exec_sound.sh; do
        if [[ -f "$SCRIPT_DIR/$f" ]]; then
            cp "$SCRIPT_DIR/$f" "$REPO_DIR/$f"
            info "  Copied $f"
        else
            warn "$f not found next to install.sh — skipping."
        fi
    done
    # Initialise a local git repo so exec_sound.sh can do 'git fetch'
    if [[ ! -d "$REPO_DIR/.git" ]]; then
        git -C "$REPO_DIR" init -q
        git -C "$REPO_DIR" add .
        git -C "$REPO_DIR" commit -q -m "Initial install" || true
    fi
fi

# ---------- compile -----------------------------------------------------------

info "Compiling SoundServer.java..."
javac -d "$BIN_DIR" "$REPO_DIR/SoundServer.java" || error "Compilation failed."
info "Compilation successful."

# ---------- install exec_sound.sh into ~/.local/bin --------------------------

info "Installing launcher to $EXEC_DEST ..."
cp "$REPO_DIR/exec_sound.sh" "$EXEC_DEST"
chmod +x "$EXEC_DEST"

# Make sure ~/.local/bin is on PATH (add to shell rc if missing)
add_to_path() {
    local RC="$1"
    if [[ -f "$RC" ]] && grep -q 'local/bin' "$RC"; then
        return  # already there
    fi
    {
        echo ''
        echo '# Added by SoundServer installer'
        echo 'export PATH="$HOME/.local/bin:$PATH"'
    } >> "$RC"
    info "Added ~/.local/bin to PATH in $RC"
}

add_to_path "$HOME/.bashrc"
[[ -f "$HOME/.zshrc" ]] && add_to_path "$HOME/.zshrc"

# ---------- systemd user service (most reliable autostart) -------------------

info "Creating systemd user service..."

cat > "$SYSTEMD_DIR/soundserver.service" <<EOF
[Unit]
Description=SoundServer — remote audio control daemon
After=network.target sound.target

[Service]
Type=forking
ExecStart=$EXEC_DEST
PIDFile=$INSTALL_DIR/soundserver.pid
Restart=on-failure
RestartSec=5
StandardOutput=append:$LOG_DIR/soundserver.log
StandardError=append:$LOG_DIR/soundserver.log

[Install]
WantedBy=default.target
EOF

# Enable the service (starts at user login; no sudo needed for user units)
if systemctl --user daemon-reload 2>/dev/null && systemctl --user enable soundserver.service 2>/dev/null; then
    info "systemd user service enabled — SoundServer will start at login."
else
    warn "systemctl --user not available. Falling back to XDG autostart only."
fi

# ---------- XDG autostart (fallback for non-systemd DEs) --------------------

info "Creating XDG autostart entry..."

cat > "$AUTOSTART_DIR/soundserver.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=SoundServer
Comment=Remote audio control daemon
Exec=$EXEC_DEST
Hidden=false
NoDisplay=false
X-GNOME-Autostart-enabled=true
EOF

info "XDG autostart entry created at $AUTOSTART_DIR/soundserver.desktop"

# ---------- start the server right now ----------------------------------------

info "Starting SoundServer now..."
"$EXEC_DEST" && info "SoundServer is running." || warn "Could not start server (it will start automatically at next login)."

# ---------- done --------------------------------------------------------------

echo ""
info "═══════════════════════════════════════════════════════════"
info " Installation complete!"
info ""
info "  Server files : $INSTALL_DIR/"
info "  Sounds dir   : $SOUNDS_DIR/"
info "  Launcher     : $EXEC_DEST  (call it as: soundserver)"
info "  Logs         : $LOG_DIR/soundserver.log"
info ""
info "  The server starts automatically at every login."
info "  To start/restart it manually:  soundserver"
info "  To stop it:  kill \$(cat $INSTALL_DIR/soundserver.pid)"
info ""
if ! echo "$PATH" | grep -q "$HOME/.local/bin"; then
    warn "~/.local/bin is not yet in your current PATH."
    warn "Run: source ~/.bashrc   (or open a new terminal)"
fi
info "═══════════════════════════════════════════════════════════"
