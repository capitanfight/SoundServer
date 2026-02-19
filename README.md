# Java Sound Server & Client

A simple Java server-client application that lets you remotely play sounds on another PC over the network.

## Features

- Play, stop, and loop sounds remotely from any machine on the LAN
- Upload and delete sound files from the client
- Automatic server discovery — no need to know the server IP
- Set the server PC's master volume remotely
- Auto-updating: the server checks GitHub for new code on every start
- Systemd user service + XDG autostart for automatic startup at login

## Requirements

- Java JDK 11 or higher (`java`, `javac`)
- `git`
- A sound file in WAV, AIFF, or AU format
- Network connectivity between server and client PCs

---

## Installation (recommended)

The installer handles everything: cloning, compiling, autostart setup, and first launch.
**No GitHub account is required** — the repository is public and cloned over HTTPS.

```bash
# Clone the repo (no login needed — public HTTPS URL)
git clone https://github.com/YOUR_USERNAME/soundserver.git
cd soundserver

# Run the installer, pointing it at the same public repo
bash install.sh --repo https://github.com/YOUR_USERNAME/soundserver.git
```

What `install.sh` does:
1. Clones the repo into `~/.soundserver/repo/`
2. Compiles `SoundServer.java`
3. Installs the `soundserver` launcher into `~/.local/bin/`
4. Sets up a **systemd user service** (starts automatically at login)
5. Sets up an **XDG autostart entry** as a fallback
6. Starts the server immediately

> **No `sudo` required** — everything installs under your home directory.

---

## Auto-updates from GitHub

Every time the server starts (via `soundserver` or at login), `exec_sound.sh`:

1. Runs `git fetch origin` against the public GitHub repo
2. Compares your local commit to the remote
3. If there is a new version, pulls and recompiles automatically

Because the repo is public, **no GitHub account or token is needed** for updates.
The fetch uses the HTTPS URL you provided during installation.

To verify your remote is set correctly:

```bash
git -C ~/.soundserver/repo remote -v
# Should show: origin  https://github.com/YOUR_USERNAME/soundserver.git
```

If the remote is missing (e.g. you ran `install.sh` without `--repo`), add it now:

```bash
git -C ~/.soundserver/repo remote add origin https://github.com/YOUR_USERNAME/soundserver.git
git -C ~/.soundserver/repo fetch origin
git -C ~/.soundserver/repo branch --set-upstream-to=origin/main main
```

---

## Manual Setup (without installer)

### 1. Compile

```bash
javac SoundServer.java
javac SoundClient.java
```

### 2. Prepare a sound file

```bash
# Generate a test beep (requires sox)
sox -n beep.wav synth 0.5 sine 440

# Or convert an existing file (requires ffmpeg)
ffmpeg -i input.mp3 -acodec pcm_s16le output.wav
```

### 3. Allow ports through the firewall (if needed)

```bash
sudo ufw allow 8888/tcp
sudo ufw allow 8887/udp
# or with firewalld:
sudo firewall-cmd --add-port=8888/tcp --permanent
sudo firewall-cmd --add-port=8887/udp --permanent
sudo firewall-cmd --reload
```

### 4. Start the server

```bash
java -cp . SoundServer
```

### 5. Connect with the client (from any machine on the LAN)

```bash
java -cp . SoundClient
```

The client auto-discovers servers on the local network via UDP broadcast.
To connect directly if discovery does not work:

```bash
java SoundClient <server-ip-address>
```

---

## Available Commands (from the client)

| Command              | Description                              |
|----------------------|------------------------------------------|
| `UPLOAD <file-path>` | Upload a sound file to the server        |
| `PLAY <filename>`    | Play a sound file on the server          |
| `STOP`               | Stop playback                            |
| `LOOP`               | Toggle loop mode on/off                  |
| `LIST`               | List all sounds stored on the server     |
| `DELETE <filename>`  | Delete a sound from the server           |
| `STATUS`             | Show playback status and loop state      |
| `VOLUME <0-100>`     | Set the server PC's master volume        |
| `QUIT`               | Disconnect                               |

---

## Managing the Server

```bash
# Start / restart the server
soundserver

# Stop the server
kill $(cat ~/.soundserver/soundserver.pid)

# View logs
tail -f ~/.soundserver/logs/soundserver.log

# Check systemd service status
systemctl --user status soundserver
```

---

## Troubleshooting

**"Connection refused"**
Confirm the server is running (`cat ~/.soundserver/soundserver.pid`) and that port 8888 is open in the firewall.

**"No sound device found" / "Line unavailable"**
Test audio with `speaker-test -t wav`. Restart PulseAudio with `pulseaudio -k && pulseaudio --start`, or PipeWire with `systemctl --user restart pipewire`.

**Sound file format issues**
Use 16-bit PCM WAV files for best compatibility: `ffmpeg -i input.mp3 -acodec pcm_s16le output.wav`.

**Auto-update not working**
Ensure `origin` is set to a public HTTPS URL (see above). Test manually with `git -C ~/.soundserver/repo fetch origin`. If behind a proxy: `git config --global http.proxy http://proxy:port`.

**`soundserver` command not found after install**
```bash
source ~/.bashrc   # or open a new terminal
```

---

## Network Ports

| Port | Protocol | Purpose                          |
|------|----------|----------------------------------|
| 8888 | TCP      | Client–server communication      |
| 8887 | UDP      | Server auto-discovery (broadcast)|

---

## License

Free to use and modify.
