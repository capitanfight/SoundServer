import javax.sound.sampled.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SoundServer {
    private static final int PORT = 8888;
    private static final int DISCOVERY_PORT = 8887;
    private static final String DISCOVERY_REQUEST = "SOUND_SERVER_DISCOVERY";
    private static final String SOUNDS_DIR = System.getProperty("user.home") + "/.soundserver/sounds";
    private static final Map<String, Clip> loadedClips = new ConcurrentHashMap<>();
    private static Clip currentlyPlayingClip = null;
    private static String currentlyPlayingName = null;
    private static boolean isPlaying = false;
    private static boolean shouldLoop = false;
    private static String serverName;

    public static void main(String[] args) {
        serverName = getServerName();
        System.out.println("Server name: " + serverName);

        File soundsDir = new File(SOUNDS_DIR);
        if (!soundsDir.exists()) {
            soundsDir.mkdirs();
            System.out.println("Created sounds directory: " + SOUNDS_DIR);
        }

        loadExistingSounds();

        Thread discoveryThread = new Thread(() -> startDiscoveryService());
        discoveryThread.setDaemon(true);
        discoveryThread.start();

        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("Server started on port " + PORT);
            System.out.println("Discovery service running on port " + DISCOVERY_PORT);
            System.out.println("Sound files directory: " + soundsDir.getAbsolutePath());
            System.out.println("Waiting for client connections...");

            while (true) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    System.out.println("Client connected: " + clientSocket.getInetAddress());
                    new Thread(() -> handleClient(clientSocket)).start();
                } catch (IOException e) {
                    System.err.println("Error accepting client: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("Could not start server: " + e.getMessage());
            System.exit(1);
        }
    }

    private static String getServerName() {
        String name = System.getenv("USER");
        if (name == null || name.isEmpty()) name = System.getenv("USERNAME");
        if (name == null || name.isEmpty()) {
            String homeDir = System.getProperty("user.home");
            if (homeDir != null) name = new File(homeDir).getName();
        }
        if (name == null || name.isEmpty()) {
            try { name = InetAddress.getLocalHost().getHostName(); }
            catch (UnknownHostException e) { name = "Unknown"; }
        }
        return name;
    }

    private static void startDiscoveryService() {
        try (DatagramSocket socket = new DatagramSocket(DISCOVERY_PORT)) {
            socket.setBroadcast(true);
            System.out.println("Discovery service started on UDP port " + DISCOVERY_PORT);
            byte[] buffer = new byte[1024];
            while (true) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    String message = new String(packet.getData(), 0, packet.getLength());
                    if (message.trim().equals(DISCOVERY_REQUEST)) {
                        System.out.println("Discovery request received from " + packet.getAddress());
                        String localIP = getLocalIPAddress();
                        String response = localIP + ":" + PORT + ":" + serverName;
                        byte[] responseData = response.getBytes();
                        DatagramPacket responsePacket = new DatagramPacket(
                                responseData, responseData.length,
                                packet.getAddress(), packet.getPort());
                        socket.send(responsePacket);
                        System.out.println("Sent discovery response to " + packet.getAddress());
                    }
                } catch (IOException e) {
                    System.err.println("Discovery service error: " + e.getMessage());
                }
            }
        } catch (SocketException e) {
            System.err.println("Could not start discovery service: " + e.getMessage());
        }
    }

    private static String getLocalIPAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (SocketException e) {
            System.err.println("Error getting local IP: " + e.getMessage());
        }
        try { return InetAddress.getLocalHost().getHostAddress(); }
        catch (UnknownHostException e) { return "127.0.0.1"; }
    }

    private static void loadExistingSounds() {
        File soundsDir = new File(SOUNDS_DIR);
        File[] files = soundsDir.listFiles((dir, name) ->
                name.toLowerCase().endsWith(".wav") ||
                        name.toLowerCase().endsWith(".au") ||
                        name.toLowerCase().endsWith(".aiff"));
        if (files != null && files.length > 0) {
            System.out.println("Loading existing sound files...");
            for (File file : files) {
                try {
                    loadSound(file.getName());
                    System.out.println("  - Loaded: " + file.getName());
                } catch (Exception e) {
                    System.err.println("  - Failed to load " + file.getName() + ": " + e.getMessage());
                }
            }
        }
    }

    private static void loadSound(String filename) throws Exception {
        File soundFile = new File(SOUNDS_DIR, filename);
        AudioInputStream audioStream = AudioSystem.getAudioInputStream(soundFile);
        Clip clip = AudioSystem.getClip();
        clip.open(audioStream);
        loadedClips.put(filename, clip);
    }

    private static void handleClient(Socket clientSocket) {
        try {
            String command;
            while ((command = readLine(clientSocket)) != null) {
                command = command.trim();
                System.out.println("Received command: " + command);
                String[] parts = command.split(" ", 2);
                String action = parts[0].toUpperCase();
                try {
                    switch (action) {
                        case "UPLOAD":
                            if (parts.length < 2) { sendResponse(clientSocket, "ERROR: Missing filename"); break; }
                            sendResponse(clientSocket, handleUpload(clientSocket, parts[1]));
                            break;
                        case "PLAY":
                            if (parts.length < 2) { sendResponse(clientSocket, "ERROR: Missing filename"); break; }
                            sendResponse(clientSocket, playSound(parts[1]));
                            break;
                        case "STOP":
                            sendResponse(clientSocket, stopSound());
                            break;
                        case "LOOP":
                            sendResponse(clientSocket, toggleLoop());
                            break;
                        case "LIST":
                            sendResponse(clientSocket, listSounds());
                            break;
                        case "DELETE":
                            if (parts.length < 2) { sendResponse(clientSocket, "ERROR: Missing filename"); break; }
                            sendResponse(clientSocket, deleteSound(parts[1]));
                            break;
                        case "STATUS":
                            String status = isPlaying ? "Playing: " + currentlyPlayingName : "Stopped";
                            status += "\0Loop: " + (shouldLoop ? "on" : "off");
                            sendResponse(clientSocket, status);
                            break;
                        case "VOLUME":
                            if (parts.length < 2) { sendResponse(clientSocket, "ERROR: Missing volume level (0-100)"); break; }
                            sendResponse(clientSocket, setVolume(parts[1]));
                            break;
                        default:
                            sendResponse(clientSocket, "ERROR: Unknown command: " + action);
                    }
                } catch (Exception e) {
                    sendResponse(clientSocket, "ERROR: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        } catch (IOException e) {
            System.err.println("Client connection error: " + e.getMessage());
        } finally {
            try { clientSocket.close(); } catch (IOException e) { System.err.println("Error closing client socket: " + e.getMessage()); }
        }
    }

    private static String readLine(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
        }
        return sb.length() > 0 || c != -1 ? sb.toString() : null;
    }

    private static String toggleLoop() {
        shouldLoop = !shouldLoop;
        return shouldLoop ? "Loop on" : "Loop off";
    }

    private static void sendResponse(Socket socket, String response) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write((response + "\n").getBytes());
        out.flush();
    }

    /**
     * Sets the system master volume using amixer (ALSA) or pactl (PulseAudio/PipeWire).
     * No sudo required — these tools work at user level.
     * @param levelStr volume level as string, 0–100
     */
    private static String setVolume(String levelStr) {
        try {
            int level = Integer.parseInt(levelStr.trim());
            if (level < 0 || level > 100) {
                return "ERROR: Volume must be between 0 and 100";
            }

            // Try pactl first (PulseAudio / PipeWire — most modern desktops)
            String[] pactlCmd = {"pactl", "set-sink-volume", "@DEFAULT_SINK@", level + "%"};
            if (runCommand(pactlCmd)) {
                System.out.println("Volume set to " + level + "% via pactl");
                return "OK: Volume set to " + level + "%";
            }

            // Fallback: amixer (ALSA)
            String[] amixerCmd = {"amixer", "-q", "sset", "Master", level + "%"};
            if (runCommand(amixerCmd)) {
                System.out.println("Volume set to " + level + "% via amixer");
                return "OK: Volume set to " + level + "%";
            }

            return "ERROR: Could not set volume (neither pactl nor amixer succeeded)";

        } catch (NumberFormatException e) {
            return "ERROR: Invalid volume level: " + levelStr + " (must be 0-100)";
        }
    }

    /** Runs a command, returns true if exit code is 0. */
    private static boolean runCommand(String[] cmd) {
        try {
            Process p = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start();
            // Drain output so the process doesn't block
            p.getInputStream().transferTo(OutputStream.nullOutputStream());
            int exit = p.waitFor();
            return exit == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String handleUpload(Socket clientSocket, String filename) {
        try {
            filename = new File(filename).getName();
            DataInputStream dataIn = new DataInputStream(clientSocket.getInputStream());
            long fileSize = dataIn.readLong();
            System.out.println("Receiving file: " + filename + " (" + fileSize + " bytes)");
            File outputFile = new File(SOUNDS_DIR, filename);
            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                byte[] buffer = new byte[4096];
                long remaining = fileSize;
                int read;
                while (remaining > 0) {
                    read = dataIn.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (read == -1) break;
                    fos.write(buffer, 0, read);
                    remaining -= read;
                }
            }
            System.out.println("File saved: " + outputFile.getAbsolutePath());
            loadSound(filename);
            return "OK: File uploaded successfully: " + filename;
        } catch (Exception e) {
            e.printStackTrace();
            return "ERROR: Upload failed: " + e.getMessage();
        }
    }

    private static synchronized String playSound(String filename) {
        try {
            if (isPlaying) stopSound();
            if (!loadedClips.containsKey(filename)) return "ERROR: Sound file not found: " + filename;
            currentlyPlayingClip = loadedClips.get(filename);
            currentlyPlayingName = filename;
            currentlyPlayingClip.setFramePosition(0);
            if (shouldLoop) {
                currentlyPlayingClip.loop(Clip.LOOP_CONTINUOUSLY);
                isPlaying = true;
            } else {
                currentlyPlayingClip.start();
            }
            System.out.println("Playing sound: " + filename);
            return "OK: Playing " + filename;
        } catch (Exception e) {
            e.printStackTrace();
            return "ERROR: Failed to play sound: " + e.getMessage();
        }
    }

    private static synchronized String stopSound() {
        if (isPlaying && currentlyPlayingClip != null) {
            currentlyPlayingClip.stop();
            String stoppedSound = currentlyPlayingName;
            isPlaying = false;
            currentlyPlayingClip = null;
            currentlyPlayingName = null;
            System.out.println("Sound stopped");
            return "OK: Stopped playing " + stoppedSound;
        }
        return "OK: No sound was playing";
    }

    private static String listSounds() {
        if (loadedClips.isEmpty()) return "OK: No sounds available";
        StringBuilder sb = new StringBuilder("OK: Available sounds:\0");
        for (String filename : loadedClips.keySet()) {
            File file = new File(SOUNDS_DIR, filename);
            sb.append("  - ").append(filename).append(" (").append(file.length()).append(" bytes)\0");
        }
        return sb.toString().trim();
    }

    private static synchronized String deleteSound(String filename) {
        try {
            if (isPlaying && filename.equals(currentlyPlayingName)) stopSound();
            Clip clip = loadedClips.remove(filename);
            if (clip != null) clip.close();
            File file = new File(SOUNDS_DIR, filename);
            if (file.exists() && file.delete()) {
                System.out.println("Deleted sound: " + filename);
                return "OK: Deleted " + filename;
            } else {
                return "ERROR: File not found: " + filename;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return "ERROR: Failed to delete: " + e.getMessage();
        }
    }
}