import java.io.*;
import java.net.*;
import java.util.*;

public class SoundClient {
    private static final int DISCOVERY_PORT = 8887;
    private static final String DISCOVERY_REQUEST = "SOUND_SERVER_DISCOVERY";
    private static final int DISCOVERY_TIMEOUT = 3000;

    private static Socket socket;
    private static DataOutputStream dataOut;
    private static DataInputStream dataIn;

    public static void main(String[] args) {
        try {
            List<ServerInfo> servers = discoverServers();

            if (servers.isEmpty()) {
                System.out.println("No servers found on the network.");
                System.out.println("\nYou can also connect directly by running:");
                System.out.println("java SoundClient <server-ip-address>");
                return;
            }

            System.out.println("\nAvailable servers:");
            for (int i = 0; i < servers.size(); i++) {
                ServerInfo server = servers.get(i);
                System.out.printf("%d) %s (%s:%d)%n", i + 1, server.name, server.ipAddress, server.port);
            }

            Scanner scanner = new Scanner(System.in);
            ServerInfo selectedServer = null;

            while (selectedServer == null) {
                System.out.print("\nSelect a server (1-" + servers.size() + "): ");
                try {
                    int choice = Integer.parseInt(scanner.nextLine().trim());
                    if (choice >= 1 && choice <= servers.size()) {
                        selectedServer = servers.get(choice - 1);
                    } else {
                        System.out.println("Invalid selection. Please try again.");
                    }
                } catch (NumberFormatException e) {
                    System.out.println("Invalid input. Please enter a number.");
                }
            }

            System.out.println("\nConnecting to " + selectedServer.name +
                    " at " + selectedServer.ipAddress + ":" + selectedServer.port + "...");
            connectToServer(selectedServer.ipAddress, selectedServer.port);

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static List<ServerInfo> discoverServers() {
        List<ServerInfo> servers = new ArrayList<>();

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.setSoTimeout(DISCOVERY_TIMEOUT);

            byte[] requestData = DISCOVERY_REQUEST.getBytes();
            List<InetAddress> broadcastAddresses = getBroadcastAddresses();

            System.out.println("Searching for servers on the network...");

            for (InetAddress broadcastAddr : broadcastAddresses) {
                try {
                    DatagramPacket packet = new DatagramPacket(
                            requestData, requestData.length, broadcastAddr, DISCOVERY_PORT);
                    socket.send(packet);
                    System.out.println("Sent discovery request to " + broadcastAddr.getHostAddress());
                } catch (IOException e) {
                    System.err.println("Could not send to " + broadcastAddr + ": " + e.getMessage());
                }
            }

            // Fallback broadcast
            try {
                InetAddress broadcast = InetAddress.getByName("255.255.255.255");
                socket.send(new DatagramPacket(requestData, requestData.length, broadcast, DISCOVERY_PORT));
                System.out.println("Sent discovery request to 255.255.255.255");
            } catch (IOException ignored) {}

            byte[] buffer = new byte[1024];
            long startTime = System.currentTimeMillis();
            Set<String> seenServers = new HashSet<>();

            while (System.currentTimeMillis() - startTime < DISCOVERY_TIMEOUT) {
                try {
                    DatagramPacket responsePacket = new DatagramPacket(buffer, buffer.length);
                    socket.receive(responsePacket);
                    String response = new String(responsePacket.getData(), 0, responsePacket.getLength());
                    String[] parts = response.split(":", 3);
                    if (parts.length == 3) {
                        String serverKey = parts[0] + ":" + parts[1];
                        if (!seenServers.contains(serverKey)) {
                            seenServers.add(serverKey);
                            ServerInfo server = new ServerInfo();
                            server.ipAddress = parts[0];
                            server.port = Integer.parseInt(parts[1]);
                            server.name = parts[2];
                            servers.add(server);
                            System.out.println("Found server: " + server.name + " at " + server.ipAddress + ":" + server.port);
                        }
                    }
                } catch (SocketTimeoutException e) {
                    break;
                } catch (IOException e) {
                    System.err.println("Error receiving response: " + e.getMessage());
                }
            }

        } catch (SocketException e) {
            System.err.println("Could not create discovery socket: " + e.getMessage());
        }

        return servers;
    }

    private static List<InetAddress> getBroadcastAddresses() {
        List<InetAddress> broadcastList = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;
                for (InterfaceAddress addr : iface.getInterfaceAddresses()) {
                    InetAddress broadcast = addr.getBroadcast();
                    if (broadcast != null) broadcastList.add(broadcast);
                }
            }
        } catch (SocketException e) {
            System.err.println("Error getting broadcast addresses: " + e.getMessage());
        }
        return broadcastList;
    }

    private static void connectToServer(String serverAddress, int serverPort) {
        try {
            socket = new Socket(serverAddress, serverPort);
            dataOut = new DataOutputStream(socket.getOutputStream());
            dataIn = new DataInputStream(socket.getInputStream());

            System.out.println("Connected to server at " + serverAddress + ":" + serverPort);
            System.out.println("\nAvailable commands:");
            System.out.println("  UPLOAD <file-path>   - Upload a sound file to the server");
            System.out.println("  PLAY <filename>      - Play a sound file");
            System.out.println("  STOP                 - Stop playing sound");
            System.out.println("  LOOP                 - Toggle loop mode");
            System.out.println("  LIST                 - List all sounds on server");
            System.out.println("  DELETE <filename>    - Delete a sound from server");
            System.out.println("  STATUS               - Check playback status");
            System.out.println("  VOLUME <0-100>       - Set server PC master volume");
            System.out.println("  QUIT                 - Exit client");
            System.out.println();

            Scanner scanner = new Scanner(System.in);

            while (true) {
                System.out.print("Enter command: ");
                String input = scanner.nextLine().trim();

                if (input.equalsIgnoreCase("QUIT")) {
                    System.out.println("Disconnecting...");
                    break;
                }

                String[] parts = input.split(" ", 2);
                String command = parts[0].toUpperCase();

                try {
                    if (command.equals("UPLOAD")) {
                        if (parts.length < 2) {
                            System.out.println("Error: Please specify file path");
                            System.out.println("Usage: UPLOAD <file-path>");
                            continue;
                        }
                        uploadFile(parts[1]);
                    } else if (command.equals("VOLUME")) {
                        if (parts.length < 2) {
                            System.out.println("Error: Please specify volume level (0-100)");
                            System.out.println("Usage: VOLUME <0-100>");
                            continue;
                        }
                        // Validate locally before sending
                        try {
                            int vol = Integer.parseInt(parts[1].trim());
                            if (vol < 0 || vol > 100) {
                                System.out.println("Error: Volume must be between 0 and 100");
                                continue;
                            }
                        } catch (NumberFormatException e) {
                            System.out.println("Error: Volume must be a number between 0 and 100");
                            continue;
                        }
                        sendCommand(input);
                        displayResponse(receiveResponse());
                    } else {
                        sendCommand(input);
                        displayResponse(receiveResponse());
                    }
                } catch (IOException e) {
                    System.err.println("Communication error: " + e.getMessage());
                    break;
                }
            }

            scanner.close();
            socket.close();

        } catch (UnknownHostException e) {
            System.err.println("Unknown host: " + serverAddress);
        } catch (IOException e) {
            System.err.println("Connection error: " + e.getMessage());
        }
    }

    private static void uploadFile(String filePath) throws IOException {
        File file = new File(filePath);
        if (!file.exists()) { System.out.println("Error: File not found: " + filePath); return; }
        if (!file.isFile()) { System.out.println("Error: Not a file: " + filePath); return; }

        String filename = file.getName();
        String ext = filename.contains(".") ? filename.substring(filename.lastIndexOf('.') + 1).toLowerCase() : "";
        if (!ext.equals("wav") && !ext.equals("au") && !ext.equals("aiff")) {
            System.out.println("Warning: File should be WAV, AU, or AIFF format");
            System.out.print("Continue anyway? (y/n): ");
            Scanner scanner = new Scanner(System.in);
            if (!scanner.nextLine().trim().equalsIgnoreCase("y")) return;
        }

        System.out.println("Uploading: " + filename + " (" + file.length() + " bytes)");
        sendCommand("UPLOAD " + filename);
        dataOut.writeLong(file.length());
        dataOut.flush();

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[4096];
            int read;
            long totalSent = 0;
            while ((read = fis.read(buffer)) != -1) {
                dataOut.write(buffer, 0, read);
                totalSent += read;
                int percent = (int) ((totalSent * 100) / file.length());
                System.out.print("\rProgress: " + percent + "%");
            }
            System.out.println();
            dataOut.flush();
        }

        displayResponse(receiveResponse());
    }

    private static void sendCommand(String command) throws IOException {
        dataOut.write((command + "\n").getBytes());
        dataOut.flush();
    }

    private static String receiveResponse() throws IOException {
        StringBuilder response = new StringBuilder();
        int c;
        while ((c = dataIn.read()) != -1) {
            if (c == '\n') break;
            c = c == '\0' ? '\n' : c;
            response.append((char) c);
        }
        return response.toString();
    }

    private static void displayResponse(String response) {
        if (response.startsWith("OK:")) {
            System.out.println("✔ " + response.substring(3).trim());
        } else if (response.startsWith("ERROR:")) {
            System.out.println("✘ " + response.substring(6).trim());
        } else {
            System.out.println(response);
        }
    }

    private static class ServerInfo {
        String ipAddress;
        int port;
        String name;
    }
}