import java.io.*;
import java.net.*;
import java.util.*;

public class SoundClient {
    private static final int DISCOVERY_PORT = 8887;
    private static final String DISCOVERY_REQUEST = "SOUND_SERVER_DISCOVERY";
    private static final int DISCOVERY_TIMEOUT = 3000; // 3 seconds

    private static Socket socket;
    private static DataOutputStream dataOut;
    private static DataInputStream dataIn;

    public static void main(String[] args) {
        try {
            // Perform server discovery
            List<ServerInfo> servers = discoverServers();

            if (servers.isEmpty()) {
                System.out.println("No servers found on the network.");
                System.out.println("\nYou can also connect directly by running:");
                System.out.println("java SoundClient <server-ip-address>");
                return;
            }

            // Display available servers
            System.out.println("\nAvailable servers:");
            for (int i = 0; i < servers.size(); i++) {
                ServerInfo server = servers.get(i);
                System.out.printf("%d) %s (%s:%d)%n",
                        i + 1, server.name, server.ipAddress, server.port);
            }

            // Let user select a server
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

            // Connect to selected server
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

            // Prepare discovery request
            byte[] requestData = DISCOVERY_REQUEST.getBytes();

            // Get broadcast addresses
            List<InetAddress> broadcastAddresses = getBroadcastAddresses();

            System.out.println("Searching for servers on the network...");

            // Send discovery request to all broadcast addresses
            for (InetAddress broadcastAddr : broadcastAddresses) {
                try {
                    DatagramPacket packet = new DatagramPacket(
                            requestData,
                            requestData.length,
                            broadcastAddr,
                            DISCOVERY_PORT
                    );
                    socket.send(packet);
                    System.out.println("Sent discovery request to " + broadcastAddr.getHostAddress());
                } catch (IOException e) {
                    System.err.println("Could not send to " + broadcastAddr + ": " + e.getMessage());
                }
            }

            // Also try 255.255.255.255 as fallback
            try {
                InetAddress broadcast = InetAddress.getByName("255.255.255.255");
                DatagramPacket packet = new DatagramPacket(
                        requestData,
                        requestData.length,
                        broadcast,
                        DISCOVERY_PORT
                );
                socket.send(packet);
                System.out.println("Sent discovery request to 255.255.255.255");
            } catch (IOException e) {
                // Ignore if this fails
            }

            // Listen for responses
            byte[] buffer = new byte[1024];
            long startTime = System.currentTimeMillis();
            Set<String> seenServers = new HashSet<>();

            while (System.currentTimeMillis() - startTime < DISCOVERY_TIMEOUT) {
                try {
                    DatagramPacket responsePacket = new DatagramPacket(buffer, buffer.length);
                    socket.receive(responsePacket);

                    String response = new String(responsePacket.getData(), 0, responsePacket.getLength());

                    // Parse response: IP:PORT:NAME
                    String[] parts = response.split(":", 3);
                    if (parts.length == 3) {
                        String serverKey = parts[0] + ":" + parts[1];

                        // Avoid duplicates
                        if (!seenServers.contains(serverKey)) {
                            seenServers.add(serverKey);

                            ServerInfo server = new ServerInfo();
                            server.ipAddress = parts[0];
                            server.port = Integer.parseInt(parts[1]);
                            server.name = parts[2];

                            servers.add(server);
                            System.out.println("Found server: " + server.name +
                                    " at " + server.ipAddress + ":" + server.port);
                        }
                    }
                } catch (SocketTimeoutException e) {
                    // Timeout is expected, continue
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

                if (iface.isLoopback() || !iface.isUp()) {
                    continue;
                }

                for (InterfaceAddress addr : iface.getInterfaceAddresses()) {
                    InetAddress broadcast = addr.getBroadcast();
                    if (broadcast != null) {
                        broadcastList.add(broadcast);
                    }
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
            System.out.println("  UPLOAD <file-path>  - Upload a sound file to the server");
            System.out.println("  PLAY <filename>     - Play a sound file in loop");
            System.out.println("  STOP                - Stop playing sound");
            System.out.println("  LOOP                - Toggle loop");
            System.out.println("  LIST                - List all sounds on server");
            System.out.println("  DELETE <filename>   - Delete a sound from server");
            System.out.println("  STATUS              - Check if sound is playing");
            System.out.println("  QUIT                - Exit client");
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
                    } else {
                        // Send command and receive response
                        sendCommand(input);
                        String response = receiveResponse();
                        displayResponse(response);
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

        if (!file.exists()) {
            System.out.println("Error: File not found: " + filePath);
            return;
        }

        if (!file.isFile()) {
            System.out.println("Error: Not a file: " + filePath);
            return;
        }

        // Check file extension
        String filename = file.getName();
        String ext = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
        if (!ext.equals("wav") && !ext.equals("au") && !ext.equals("aiff")) {
            System.out.println("Warning: File should be WAV, AU, or AIFF format");
            System.out.print("Continue anyway? (y/n): ");
            Scanner scanner = new Scanner(System.in);
            if (!scanner.nextLine().trim().equalsIgnoreCase("y")) {
                return;
            }
        }

        System.out.println("Uploading: " + filename + " (" + file.length() + " bytes)");

        // Send UPLOAD command with filename
        sendCommand("UPLOAD " + filename);

        // Send file size
        dataOut.writeLong(file.length());
        dataOut.flush();

        // Send file data
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[4096];
            int read;
            long totalSent = 0;

            while ((read = fis.read(buffer)) != -1) {
                dataOut.write(buffer, 0, read);
                totalSent += read;

                // Show progress
                int percent = (int) ((totalSent * 100) / file.length());
                System.out.print("\rProgress: " + percent + "%");
            }
            System.out.println();
            dataOut.flush();
        }

        // Receive response
        String response = receiveResponse();
        displayResponse(response);
    }

    private static void sendCommand(String command) throws IOException {
        dataOut.write((command + "\n").getBytes());
        dataOut.flush();
    }

    private static String receiveResponse() throws IOException {
        StringBuilder response = new StringBuilder();
        int c;

        while ((c = dataIn.read()) != -1) {
            if (c == '\n') {
                break;
            }
            c = c == '\0' ? '\n' : c;
            response.append((char) c);
        }

        return response.toString();
    }

    private static void displayResponse(String response) {
        if (response.startsWith("OK:")) {
            System.out.println("✓ " + response.substring(3).trim());
        } else if (response.startsWith("ERROR:")) {
            System.out.println("✗ " + response.substring(6).trim());
        } else {
            System.out.println(response);
        }
    }

    // Helper class to store server information
    private static class ServerInfo {
        String ipAddress;
        int port;
        String name;
    }
}