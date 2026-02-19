# Java Sound Server & Client

A simple Java server-client application that plays a sound in loop when receiving a message from a remote client.

## Features

- Server plays sound in continuous loop when triggered
- Control from remote client on another PC
- Commands: PLAY, STOP, STATUS
- Works on Linux with Java Sound API

## Requirements

- Java JDK 8 or higher
- A sound file (WAV, AIFF, or AU format)
- Network connectivity between server and client PCs

## Setup

### 1. Compile the files

On both server and client machines:

```bash
javac SoundServer1.java
javac SoundClient1.java
```

### 2. Prepare a sound file

Place a WAV file on the server machine. For testing, you can create one:

```bash
# Generate a simple beep sound using sox (install with: sudo apt install sox)
sox -n beep.wav synth 0.5 sine 440

# Or download a free sound file
wget https://www.soundjay.com/misc/sounds/bell-ringing-05.wav -O sound.wav
```

### 3. Find server IP address

On the server machine:

```bash
ip addr show
# or
hostname -I
```

Note the IP address (e.g., 192.168.1.100)

### 4. Configure firewall (if needed)

On the server machine, allow port 8888:

```bash
sudo ufw allow 8888/tcp
# or with firewalld:
sudo firewall-cmd --add-port=8888/tcp --permanent
sudo firewall-cmd --reload
```

## Usage

### On the Server Machine (Linux)

Start the server with your sound file:

```bash
java SoundServer1 /path/to/your/sound.wav
```

Example:
```bash
java SoundServer1 beep.wav
```

The server will start and wait for client connections.

### On the Client Machine (Any PC on the network)

Connect to the server:

```bash
java SoundClient1 <server-ip-address>
```

Example:
```bash
java SoundClient1 192.168.1.100
```

### Available Commands

Once connected, you can use:

- `PLAY` - Start playing the sound in loop on the server
- `STOP` - Stop playing the sound
- `STATUS` - Check if sound is currently playing
- `QUIT` - Exit the client

## Example Session

**Server output:**
```
Sound file loaded successfully: beep.wav
Server started on port 8888
Waiting for client connections...
Client connected: /192.168.1.50
Received: PLAY
Sound started playing in loop
Received: STOP
Sound stopped
```

**Client output:**
```
Connected to server at 192.168.1.100:8888

Available commands:
  PLAY   - Start playing sound in loop
  STOP   - Stop playing sound
  STATUS - Check if sound is playing
  QUIT   - Exit client

Enter command: PLAY
Server: Sound started playing in loop
Enter command: STOP
Server: Sound stopped
Enter command: QUIT
Disconnecting...
```

## Troubleshooting

### "Connection refused"
- Check that the server is running
- Verify the IP address is correct
- Check firewall settings on server

### "No sound device found"
- Ensure audio is working on Linux: `speaker-test -t wav`
- Check PulseAudio is running: `pulseaudio --check`
- Install required audio packages: `sudo apt install pulseaudio`

### "Line unavailable" error
- The audio device may be in use by another application
- Try: `pulseaudio -k` to restart audio server

### Sound file format issues
- Convert to WAV format: `ffmpeg -i input.mp3 output.wav`
- Use 16-bit PCM WAV files for best compatibility

## Network Configuration

By default, the server uses port 8888. To change it, modify the `PORT` constant in both files.

Make sure both machines are on the same network or have proper routing configured for communication.

## Advanced: Running as Background Service

To run the server as a systemd service:

1. Create a service file: `/etc/systemd/system/sound-server.service`
2. Add configuration
3. Enable and start: `sudo systemctl enable --now sound-server.service`

## License

Free to use and modify.