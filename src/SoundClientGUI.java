import javax.sound.sampled.*;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.event.*;
import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.dnd.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.List;

/**
 * SoundClientGUI — Swing GUI front-end for SoundServer.
 *
 * Features:
 *  • Auto-discovers servers on the LAN via UDP broadcast
 *  • Sound list: click a row to play, right-click to delete
 *  • Drag-and-drop or browse to upload files
 *  • Volume slider initialised to the server's current volume
 *  • Mute / Unmute buttons (calls MUTE / UNMUTE commands)
 *  • Loop toggle (checkbox)
 *  • STOP button
 *  • Status bar
 */
public class SoundClientGUI extends JFrame {

    // ── Network constants ─────────────────────────────────────────────────────
    private static final int TCP_PORT       = 8888;
    private static final int UDP_PORT       = 8887;
    private static final String DISC_MSG    = "SOUND_SERVER_DISCOVERY";
    private static final int DISC_TIMEOUT   = 3000;

    // ── Connection state ──────────────────────────────────────────────────────
    private Socket          socket;
    private DataOutputStream dataOut;
    private DataInputStream  dataIn;
    private boolean          connected = false;

    // ── UI state ──────────────────────────────────────────────────────────────
    private boolean          isMuted   = false;
    private int              volumeBeforeMute = 50;

    // ── Palette & fonts ───────────────────────────────────────────────────────
    private static final Color BG          = new Color(0x0F0F14);
    private static final Color SURFACE     = new Color(0x1A1A24);
    private static final Color SURFACE2    = new Color(0x22222F);
    private static final Color ACCENT      = new Color(0x7C5CFC);
    private static final Color ACCENT2     = new Color(0xA78BFA);
    private static final Color ACCENT_DIM  = new Color(0x3D2E80);
    private static final Color TEXT        = new Color(0xEAE8FF);
    private static final Color TEXT_DIM    = new Color(0x7A78A0);
    private static final Color SUCCESS     = new Color(0x4ADE80);
    private static final Color DANGER      = new Color(0xF87171);
    private static final Color BORDER      = new Color(0x2E2B45);

    // ── UI components we need to reference ───────────────────────────────────
    private JLabel          statusLabel;
    private JLabel          serverLabel;
    private DefaultListModel<String> soundListModel;
    private JList<String>   soundList;
    private JSlider         volumeSlider;
    private JButton         muteBtn;
    private JCheckBox       loopCheck;
    private JButton         stopBtn;
    private JLabel          nowPlayingLabel;
    private JProgressBar    uploadProgress;
    private JLabel          uploadLabel;
    private JPanel          dropZone;

    // ──────────────────────────────────────────────────────────────────────────
    //  Bootstrap
    // ──────────────────────────────────────────────────────────────────────────

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
            } catch (Exception ignored) {}
            new SoundClientGUI().showConnectionDialog();
        });
    }

    public SoundClientGUI() {
        super("SoundBoard");
        buildUI();
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) {
                disconnect();
            }
        });
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Connection
    // ──────────────────────────────────────────────────────────────────────────

    private void showConnectionDialog() {
        JDialog dlg = new JDialog(this, "Connect to server", true);
        dlg.setSize(420, 340);
        dlg.setLocationRelativeTo(null);
        dlg.setResizable(false);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BG);
        root.setBorder(BorderFactory.createEmptyBorder(20, 24, 20, 24));

        // Title
        JLabel title = makeLabel("SOUNDBOARD", 22, Font.BOLD, ACCENT2);
        title.setHorizontalAlignment(SwingConstants.CENTER);
        root.add(title, BorderLayout.NORTH);

        JPanel center = new JPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.setBackground(BG);
        center.setBorder(BorderFactory.createEmptyBorder(16, 0, 16, 0));

        // Discovered servers
        JLabel discLabel = makeLabel("Discovered servers", 11, Font.PLAIN, TEXT_DIM);
        discLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        center.add(discLabel);
        center.add(Box.createVerticalStrut(6));

        DefaultListModel<String> discModel = new DefaultListModel<>();
        JList<String> discList = makeStyledList(discModel);
        discList.setFixedCellHeight(32);
        JScrollPane discScroll = makeScrollPane(discList, 380, 90);
        discScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        center.add(discScroll);
        center.add(Box.createVerticalStrut(14));

        // Manual IP
        JLabel manualLabel = makeLabel("— or connect directly —", 11, Font.PLAIN, TEXT_DIM);
        manualLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        center.add(manualLabel);
        center.add(Box.createVerticalStrut(6));

        JTextField ipField = makeTextField("server IP address");
        ipField.setAlignmentX(Component.LEFT_ALIGNMENT);
        center.add(ipField);
        center.add(Box.createVerticalStrut(14));

        // Buttons row
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        btnRow.setBackground(BG);
        btnRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        JButton scanBtn = makeButton("Scan", SURFACE2, TEXT_DIM);
        JButton connectBtn = makeButton("Connect", ACCENT, TEXT);

        btnRow.add(scanBtn);
        btnRow.add(connectBtn);
        center.add(btnRow);

        root.add(center, BorderLayout.CENTER);
        dlg.setContentPane(root);

        // Server map: display -> ip:port
        Map<String, String> serverMap = new LinkedHashMap<>();

        Runnable doScan = () -> {
            discModel.clear();
            serverMap.clear();
            discLabel.setText("Searching…");
            new Thread(() -> {
                List<ServerInfo> found = discoverServers();
                SwingUtilities.invokeLater(() -> {
                    for (ServerInfo s : found) {
                        String key = s.name + "  (" + s.ipAddress + ")";
                        serverMap.put(key, s.ipAddress + ":" + s.port);
                        discModel.addElement(key);
                    }
                    discLabel.setText(found.isEmpty()
                            ? "No servers found"
                            : "Discovered servers (" + found.size() + ")");
                });
            }).start();
        };

        scanBtn.addActionListener(e -> doScan.run());

        connectBtn.addActionListener(e -> {
            String target = null;
            if (!discList.isSelectionEmpty()) {
                String sel = discList.getSelectedValue();
                target = serverMap.get(sel);
            } else if (!ipField.getText().isBlank() && !ipField.getText().equals("server IP address")) {
                target = ipField.getText().trim() + ":" + TCP_PORT;
            }
            if (target == null) {
                setStatus("Select a server or enter an IP.", DANGER);
                return;
            }
            String[] parts = target.split(":");
            String ip = parts[0];
            int port = parts.length > 1 ? Integer.parseInt(parts[1]) : TCP_PORT;
            if (connect(ip, port)) {
                dlg.dispose();
                setVisible(true);
            }
        });

        // Scan immediately on open
        doScan.run();

        dlg.setVisible(true);
    }

    private boolean connect(String ip, int port) {
        try {
            socket  = new Socket(ip, port);
            dataOut = new DataOutputStream(socket.getOutputStream());
            dataIn  = new DataInputStream(socket.getInputStream());
            connected = true;
            serverLabel.setText(ip + ":" + port);
            setStatus("Connected to " + ip, SUCCESS);
            loadSoundList();
            fetchAndApplyVolume();
            return true;
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this,
                    "Cannot connect to " + ip + ":" + port + "\n" + e.getMessage(),
                    "Connection failed", JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    private void disconnect() {
        connected = false;
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  UI construction
    // ──────────────────────────────────────────────────────────────────────────

    private void buildUI() {
        setSize(780, 600);
        setMinimumSize(new Dimension(680, 520));
        setLocationRelativeTo(null);
        setBackground(BG);

        JPanel root = new JPanel(new BorderLayout(0, 0));
        root.setBackground(BG);

        root.add(buildTopBar(),    BorderLayout.NORTH);
        root.add(buildCenter(),    BorderLayout.CENTER);
        root.add(buildStatusBar(), BorderLayout.SOUTH);

        setContentPane(root);
    }

    // ── Top bar ───────────────────────────────────────────────────────────────

    private JPanel buildTopBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(SURFACE);
        bar.setBorder(BorderFactory.createCompoundBorder(
            new MatteBorder(0, 0, 1, 0, BORDER),
            BorderFactory.createEmptyBorder(10, 18, 10, 18)
        ));

        // Left: logo + server name
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        left.setBackground(SURFACE);

        JLabel logo = makeLabel("◈ SOUNDBOARD", 16, Font.BOLD, ACCENT2);
        serverLabel  = makeLabel("—", 12, Font.PLAIN, TEXT_DIM);

        left.add(logo);
        left.add(makeLabel("|", 14, Font.PLAIN, BORDER));
        left.add(serverLabel);
        bar.add(left, BorderLayout.WEST);

        // Right: now-playing pill
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        right.setBackground(SURFACE);
        nowPlayingLabel = makeLabel("⏸  idle", 12, Font.PLAIN, TEXT_DIM);
        right.add(nowPlayingLabel);
        bar.add(right, BorderLayout.EAST);

        return bar;
    }

    // ── Centre split ──────────────────────────────────────────────────────────

    private JPanel buildCenter() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(BG);
        panel.setBorder(BorderFactory.createEmptyBorder(16, 16, 8, 16));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill    = GridBagConstraints.BOTH;
        gbc.insets  = new Insets(0, 0, 0, 12);
        gbc.gridy   = 0;
        gbc.weighty = 1.0;

        // Left: sound list
        gbc.gridx  = 0;
        gbc.weightx = 0.55;
        panel.add(buildSoundListPanel(), gbc);

        // Right: controls
        gbc.gridx  = 1;
        gbc.weightx = 0.45;
        gbc.insets  = new Insets(0, 0, 0, 0);
        panel.add(buildControlsPanel(), gbc);

        return panel;
    }

    // ── Sound list panel ──────────────────────────────────────────────────────

    private JPanel buildSoundListPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setBackground(BG);

        // Header row
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(BG);
        header.add(makeSectionLabel("SOUNDS"), BorderLayout.WEST);

        JButton refreshBtn = makeSmallButton("↻ Refresh");
        refreshBtn.addActionListener(e -> loadSoundList());
        header.add(refreshBtn, BorderLayout.EAST);
        panel.add(header, BorderLayout.NORTH);

        // List
        soundListModel = new DefaultListModel<>();
        soundList      = makeStyledList(soundListModel);
        soundList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        soundList.setCellRenderer(new SoundCellRenderer());

        // Click to play
        soundList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                String sel = soundList.getSelectedValue();
                if (sel == null) return;
                if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 1) {
                    playSound(sel);
                } else if (SwingUtilities.isRightMouseButton(e)) {
                    showDeletePopup(sel, e);
                }
            }
        });

        JScrollPane scroll = makeScrollPane(soundList, 0, 0);
        panel.add(scroll, BorderLayout.CENTER);

        // Upload zone below the list
        panel.add(buildDropZone(), BorderLayout.SOUTH);

        return panel;
    }

    private void showDeletePopup(String filename, MouseEvent e) {
        JPopupMenu menu = new JPopupMenu();
        menu.setBackground(SURFACE2);
        JMenuItem del = new JMenuItem("Delete \"" + filename + "\"");
        del.setBackground(SURFACE2);
        del.setForeground(DANGER);
        del.setFont(new Font("Monospaced", Font.PLAIN, 12));
        del.addActionListener(a -> deleteSound(filename));
        menu.add(del);
        menu.show(soundList, e.getX(), e.getY());
    }

    // ── Drop / upload zone ────────────────────────────────────────────────────

    private JPanel buildDropZone() {
        dropZone = new JPanel(new BorderLayout());
        dropZone.setBackground(SURFACE);
        dropZone.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createDashedBorder(ACCENT_DIM, 3, 4, 2, false),
            BorderFactory.createEmptyBorder(10, 12, 10, 12)
        ));
        dropZone.setPreferredSize(new Dimension(0, 90));
        dropZone.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        JPanel inner = new JPanel();
        inner.setLayout(new BoxLayout(inner, BoxLayout.Y_AXIS));
        inner.setBackground(SURFACE);

        uploadLabel = makeLabel("Drop a sound file here  ·  or", 12, Font.PLAIN, TEXT_DIM);
        uploadLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        JButton browseBtn = makeButton("Browse…", ACCENT_DIM, ACCENT2);
        browseBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
        browseBtn.addActionListener(e -> browseFile());

        uploadProgress = new JProgressBar(0, 100);
        uploadProgress.setStringPainted(false);
        uploadProgress.setVisible(false);
        uploadProgress.setBackground(SURFACE);
        uploadProgress.setForeground(ACCENT);
        uploadProgress.setBorderPainted(false);
        uploadProgress.setPreferredSize(new Dimension(0, 4));
        uploadProgress.setMaximumSize(new Dimension(Integer.MAX_VALUE, 4));
        uploadProgress.setAlignmentX(Component.CENTER_ALIGNMENT);

        inner.add(Box.createVerticalGlue());
        inner.add(uploadLabel);
        inner.add(Box.createVerticalStrut(6));
        inner.add(browseBtn);
        inner.add(Box.createVerticalStrut(6));
        inner.add(uploadProgress);
        inner.add(Box.createVerticalGlue());

        dropZone.add(inner, BorderLayout.CENTER);

        // Drag-and-drop
        new DropTarget(dropZone, new DropTargetAdapter() {
            @Override public void dragEnter(DropTargetDragEvent e) {
                dropZone.setBackground(ACCENT_DIM);
                inner.setBackground(ACCENT_DIM);
            }
            @Override public void dragExit(DropTargetEvent e) {
                dropZone.setBackground(SURFACE);
                inner.setBackground(SURFACE);
            }
            @Override @SuppressWarnings("unchecked")
            public void drop(DropTargetDropEvent e) {
                dropZone.setBackground(SURFACE);
                inner.setBackground(SURFACE);
                try {
                    e.acceptDrop(DnDConstants.ACTION_COPY);
                    List<File> files = (List<File>) e.getTransferable()
                            .getTransferData(DataFlavor.javaFileListFlavor);
                    if (!files.isEmpty()) uploadFile(files.get(0));
                } catch (Exception ex) {
                    setStatus("Drop error: " + ex.getMessage(), DANGER);
                }
            }
        });

        return dropZone;
    }

    // ── Controls panel ────────────────────────────────────────────────────────

    private JPanel buildControlsPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(BG);

        panel.add(makeSectionLabel("PLAYBACK"));
        panel.add(Box.createVerticalStrut(10));

        // Stop button
        stopBtn = makeButton("⏹  STOP", SURFACE2, DANGER);
        stopBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        stopBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        stopBtn.addActionListener(e -> stopSound());
        panel.add(stopBtn);
        panel.add(Box.createVerticalStrut(10));

        // Loop checkbox
        loopCheck = new JCheckBox("Loop");
        loopCheck.setBackground(BG);
        loopCheck.setForeground(TEXT);
        loopCheck.setFont(new Font("Monospaced", Font.PLAIN, 13));
        loopCheck.setFocusPainted(false);
        loopCheck.setAlignmentX(Component.LEFT_ALIGNMENT);
        loopCheck.addActionListener(e -> toggleLoop());
        styleCheckBox(loopCheck);
        panel.add(loopCheck);
        panel.add(Box.createVerticalStrut(24));

        // Volume section
        panel.add(makeSectionLabel("VOLUME"));
        panel.add(Box.createVerticalStrut(10));

        // Slider
        volumeSlider = new JSlider(0, 100, 50);
        volumeSlider.setBackground(BG);
        volumeSlider.setForeground(ACCENT2);
        volumeSlider.setMajorTickSpacing(25);
        volumeSlider.setMinorTickSpacing(5);
        volumeSlider.setPaintTicks(true);
        volumeSlider.setPaintLabels(true);
        volumeSlider.setAlignmentX(Component.LEFT_ALIGNMENT);
        volumeSlider.setMaximumSize(new Dimension(Integer.MAX_VALUE, 60));
        styleSliderLabels(volumeSlider);

        // Commit on mouse release (avoid spamming)
        volumeSlider.addMouseListener(new MouseAdapter() {
            @Override public void mouseReleased(MouseEvent e) {
                setVolume(volumeSlider.getValue());
            }
        });
        panel.add(volumeSlider);
        panel.add(Box.createVerticalStrut(10));

        // Mute / Unmute buttons row
        JPanel muteRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        muteRow.setBackground(BG);
        muteRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        muteBtn = makeButton("🔇  Mute", SURFACE2, TEXT);
        muteBtn.setPreferredSize(new Dimension(110, 32));
        muteBtn.addActionListener(e -> toggleMute());
        muteRow.add(muteBtn);
        panel.add(muteRow);

        panel.add(Box.createVerticalGlue());
        return panel;
    }

    // ── Status bar ────────────────────────────────────────────────────────────

    private JPanel buildStatusBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(SURFACE);
        bar.setBorder(BorderFactory.createCompoundBorder(
            new MatteBorder(1, 0, 0, 0, BORDER),
            BorderFactory.createEmptyBorder(5, 18, 5, 18)
        ));

        statusLabel = makeLabel("Not connected", 11, Font.PLAIN, TEXT_DIM);
        bar.add(statusLabel, BorderLayout.WEST);

        JLabel hint = makeLabel("click to play  ·  right-click to delete", 10, Font.PLAIN, TEXT_DIM);
        bar.add(hint, BorderLayout.EAST);

        return bar;
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Server actions
    // ──────────────────────────────────────────────────────────────────────────

    private void loadSoundList() {
        if (!connected) return;
        new Thread(() -> {
            try {
                String resp = sendAndReceive("LIST");
                SwingUtilities.invokeLater(() -> {
                    soundListModel.clear();
                    if (resp.startsWith("OK:")) {
                        String[] lines = resp.split("[\0\n]");
                        for (String line : lines) {
                            line = line.trim();
                            if (line.startsWith("-")) {
                                // "  - filename.wav (1234 bytes)"
                                String name = line.substring(1).trim();
                                int paren = name.indexOf('(');
                                if (paren > 0) name = name.substring(0, paren).trim();
                                if (!name.isEmpty()) soundListModel.addElement(name);
                            }
                        }
                        setStatus("Loaded " + soundListModel.size() + " sound(s)", TEXT_DIM);
                    }
                });
            } catch (IOException e) {
                setStatus("Error loading list: " + e.getMessage(), DANGER);
            }
        }).start();
    }

    private void playSound(String filename) {
        if (!connected) return;
        new Thread(() -> {
            try {
                String resp = sendAndReceive("PLAY " + filename);
                boolean ok = resp.startsWith("OK:");
                setStatus(resp.replaceFirst("^OK: ?", ""), ok ? SUCCESS : DANGER);
                if (ok) SwingUtilities.invokeLater(() ->
                    nowPlayingLabel.setText("▶  " + filename));
            } catch (IOException e) {
                setStatus("Error: " + e.getMessage(), DANGER);
            }
        }).start();
    }

    private void stopSound() {
        if (!connected) return;
        new Thread(() -> {
            try {
                String resp = sendAndReceive("STOP");
                setStatus(resp.replaceFirst("^OK: ?", ""), TEXT_DIM);
                SwingUtilities.invokeLater(() -> nowPlayingLabel.setText("⏸  idle"));
            } catch (IOException e) {
                setStatus("Error: " + e.getMessage(), DANGER);
            }
        }).start();
    }

    private void toggleLoop() {
        if (!connected) return;
        boolean want = loopCheck.isSelected();
        new Thread(() -> {
            try {
                String resp = sendAndReceive("LOOP");
                boolean isOn = resp.toLowerCase().contains("on");
                SwingUtilities.invokeLater(() -> loopCheck.setSelected(isOn));
                setStatus("Loop " + (isOn ? "enabled" : "disabled"), isOn ? ACCENT2 : TEXT_DIM);
            } catch (IOException e) {
                setStatus("Error: " + e.getMessage(), DANGER);
            }
        }).start();
    }

    private void setVolume(int level) {
        if (!connected) return;
        new Thread(() -> {
            try {
                String resp = sendAndReceive("VOLUME " + level);
                setStatus(resp.replaceFirst("^OK: ?", ""), TEXT_DIM);
            } catch (IOException e) {
                setStatus("Error: " + e.getMessage(), DANGER);
            }
        }).start();
    }

    private void toggleMute() {
        if (!connected) return;
        String cmd = isMuted ? "UNMUTE" : "MUTE";
        new Thread(() -> {
            try {
                String resp = sendAndReceive(cmd);
                boolean success = resp.startsWith("OK:");
                if (success) {
                    isMuted = !isMuted;
                    boolean nowMuted = isMuted;
                    SwingUtilities.invokeLater(() -> {
                        if (nowMuted) {
                            muteBtn.setText("🔊  Unmute");
                            volumeSlider.setEnabled(false);
                        } else {
                            muteBtn.setText("🔇  Mute");
                            volumeSlider.setEnabled(true);
                        }
                    });
                }
                setStatus(resp.replaceFirst("^OK: ?", ""), success ? TEXT_DIM : DANGER);
            } catch (IOException e) {
                setStatus("Error: " + e.getMessage(), DANGER);
            }
        }).start();
    }

    private void fetchAndApplyVolume() {
        new Thread(() -> {
            try {
                String resp = sendAndReceive("GETVOL");
                if (resp.startsWith("OK:")) {
                    int vol = Integer.parseInt(resp.substring(3).trim());
                    SwingUtilities.invokeLater(() -> volumeSlider.setValue(vol));
                }
            } catch (Exception ignored) {}
        }).start();
    }

    private void deleteSound(String filename) {
        if (!connected) return;
        int ok = JOptionPane.showConfirmDialog(this,
                "Delete \"" + filename + "\" from the server?",
                "Confirm delete", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ok != JOptionPane.YES_OPTION) return;
        new Thread(() -> {
            try {
                String resp = sendAndReceive("DELETE " + filename);
                boolean success = resp.startsWith("OK:");
                setStatus(resp.replaceFirst("^OK: ?", ""), success ? SUCCESS : DANGER);
                if (success) loadSoundList();
            } catch (IOException e) {
                setStatus("Error: " + e.getMessage(), DANGER);
            }
        }).start();
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  File upload
    // ──────────────────────────────────────────────────────────────────────────

    private void browseFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select a sound file");
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                "Sound files (WAV, AU, AIFF)", "wav", "au", "aiff", "aif"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            uploadFile(chooser.getSelectedFile());
        }
    }

    private void uploadFile(File file) {
        if (!connected) { setStatus("Not connected", DANGER); return; }

        String ext = "";
        int dot = file.getName().lastIndexOf('.');
        if (dot >= 0) ext = file.getName().substring(dot + 1).toLowerCase();
        if (!ext.equals("wav") && !ext.equals("au") && !ext.equals("aiff") && !ext.equals("aif")) {
            int choice = JOptionPane.showConfirmDialog(this,
                    file.getName() + " doesn't look like WAV/AIFF/AU.\nUpload anyway?",
                    "Format warning", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) return;
        }

        uploadLabel.setText("Uploading " + file.getName() + "…");
        uploadProgress.setValue(0);
        uploadProgress.setVisible(true);

        new Thread(() -> {
            try {
                String filename = file.getName();
                long fileSize = file.length();

                synchronized (this) {
                    dataOut.write(("UPLOAD " + filename + "\n").getBytes());
                    dataOut.flush();
                    dataOut.writeLong(fileSize);
                    dataOut.flush();
                }

                try (FileInputStream fis = new FileInputStream(file)) {
                    byte[] buf = new byte[4096];
                    int read;
                    long sent = 0;
                    while ((read = fis.read(buf)) != -1) {
                        synchronized (this) { dataOut.write(buf, 0, read); }
                        sent += read;
                        int pct = (int) ((sent * 100) / fileSize);
                        SwingUtilities.invokeLater(() -> uploadProgress.setValue(pct));
                    }
                    synchronized (this) { dataOut.flush(); }
                }

                String resp = receiveResponse();
                boolean success = resp.startsWith("OK:");
                SwingUtilities.invokeLater(() -> {
                    uploadProgress.setVisible(false);
                    uploadLabel.setText("Drop a sound file here  ·  or");
                });
                setStatus(resp.replaceFirst("^OK: ?", ""), success ? SUCCESS : DANGER);
                if (success) loadSoundList();

            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> {
                    uploadProgress.setVisible(false);
                    uploadLabel.setText("Drop a sound file here  ·  or");
                });
                setStatus("Upload failed: " + e.getMessage(), DANGER);
            }
        }).start();
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Low-level comms
    // ──────────────────────────────────────────────────────────────────────────

    private synchronized String sendAndReceive(String cmd) throws IOException {
        dataOut.write((cmd + "\n").getBytes());
        dataOut.flush();
        return receiveResponse();
    }

    private String receiveResponse() throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = dataIn.read()) != -1) {
            if (c == '\n') break;
            if (c == '\0') c = '\n';
            sb.append((char) c);
        }
        return sb.toString();
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  UDP discovery
    // ──────────────────────────────────────────────────────────────────────────

    private List<ServerInfo> discoverServers() {
        List<ServerInfo> list = new ArrayList<>();
        try (DatagramSocket ds = new DatagramSocket()) {
            ds.setBroadcast(true);
            ds.setSoTimeout(DISC_TIMEOUT);
            byte[] req = DISC_MSG.getBytes();

            // Send to all network broadcast addresses + fallback
            Set<InetAddress> addrs = new LinkedHashSet<>();
            try {
                Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
                while (ifaces.hasMoreElements()) {
                    NetworkInterface ni = ifaces.nextElement();
                    if (ni.isLoopback() || !ni.isUp()) continue;
                    for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                        if (ia.getBroadcast() != null) addrs.add(ia.getBroadcast());
                    }
                }
            } catch (SocketException ignored) {}
            addrs.add(InetAddress.getByName("255.255.255.255"));

            for (InetAddress addr : addrs) {
                try { ds.send(new DatagramPacket(req, req.length, addr, UDP_PORT)); }
                catch (IOException ignored) {}
            }

            byte[] buf = new byte[1024];
            long end = System.currentTimeMillis() + DISC_TIMEOUT;
            Set<String> seen = new HashSet<>();
            while (System.currentTimeMillis() < end) {
                try {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    ds.receive(pkt);
                    String resp = new String(pkt.getData(), 0, pkt.getLength());
                    String[] parts = resp.split(":", 3);
                    if (parts.length == 3 && seen.add(parts[0] + ":" + parts[1])) {
                        ServerInfo si = new ServerInfo();
                        si.ipAddress = parts[0];
                        si.port      = Integer.parseInt(parts[1]);
                        si.name      = parts[2];
                        list.add(si);
                    }
                } catch (SocketTimeoutException e) { break; }
                catch (IOException ignored) {}
            }
        } catch (Exception ignored) {}
        return list;
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private void setStatus(String msg, Color color) {
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText(msg);
            statusLabel.setForeground(color);
        });
    }

    private JLabel makeLabel(String text, int size, int style, Color color) {
        JLabel lbl = new JLabel(text);
        lbl.setFont(new Font("Monospaced", style, size));
        lbl.setForeground(color);
        return lbl;
    }

    private JLabel makeSectionLabel(String text) {
        JLabel lbl = makeLabel(text, 10, Font.BOLD, TEXT_DIM);
        lbl.setBorder(BorderFactory.createEmptyBorder(0, 0, 2, 0));
        return lbl;
    }

    private JButton makeButton(String text, Color bg, Color fg) {
        JButton btn = new JButton(text);
        btn.setBackground(bg);
        btn.setForeground(fg);
        btn.setFont(new Font("Monospaced", Font.PLAIN, 12));
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setBorder(BorderFactory.createEmptyBorder(7, 14, 7, 14));
        btn.addMouseListener(new MouseAdapter() {
            Color orig = bg;
            @Override public void mouseEntered(MouseEvent e) { btn.setBackground(orig.brighter()); }
            @Override public void mouseExited(MouseEvent e)  { btn.setBackground(orig); }
        });
        return btn;
    }

    private JButton makeSmallButton(String text) {
        JButton btn = makeButton(text, SURFACE2, TEXT_DIM);
        btn.setFont(new Font("Monospaced", Font.PLAIN, 11));
        btn.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        return btn;
    }

    private <T> JList<T> makeStyledList(DefaultListModel<T> model) {
        JList<T> list = new JList<>(model);
        list.setBackground(SURFACE);
        list.setForeground(TEXT);
        list.setFont(new Font("Monospaced", Font.PLAIN, 13));
        list.setSelectionBackground(ACCENT_DIM);
        list.setSelectionForeground(ACCENT2);
        list.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        return list;
    }

    private JScrollPane makeScrollPane(JComponent c, int w, int h) {
        JScrollPane sp = new JScrollPane(c);
        sp.setBackground(SURFACE);
        sp.getViewport().setBackground(SURFACE);
        sp.setBorder(BorderFactory.createLineBorder(BORDER, 1));
        sp.getVerticalScrollBar().setBackground(SURFACE2);
        if (w > 0 || h > 0)
            sp.setPreferredSize(new Dimension(w, h));
        return sp;
    }

    private JTextField makeTextField(String placeholder) {
        JTextField tf = new JTextField(placeholder);
        tf.setBackground(SURFACE2);
        tf.setForeground(TEXT_DIM);
        tf.setCaretColor(TEXT);
        tf.setFont(new Font("Monospaced", Font.PLAIN, 12));
        tf.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BORDER),
            BorderFactory.createEmptyBorder(5, 8, 5, 8)
        ));
        tf.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        tf.addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) {
                if (tf.getText().equals(placeholder)) {
                    tf.setText(""); tf.setForeground(TEXT);
                }
            }
            @Override public void focusLost(FocusEvent e) {
                if (tf.getText().isBlank()) {
                    tf.setText(placeholder); tf.setForeground(TEXT_DIM);
                }
            }
        });
        return tf;
    }

    private void styleCheckBox(JCheckBox cb) {
        cb.setUI(new javax.swing.plaf.basic.BasicCheckBoxUI() {
            @Override public void installDefaults(AbstractButton b) {
                super.installDefaults(b);
                b.setOpaque(false);
            }
        });
    }

    private void styleSliderLabels(JSlider slider) {
        slider.setUI(new javax.swing.plaf.basic.BasicSliderUI(slider) {
            @Override public void paintThumb(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(ACCENT);
                g2.fillOval(thumbRect.x, thumbRect.y, thumbRect.width, thumbRect.height);
                g2.dispose();
            }
            @Override public void paintTrack(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int cy = trackRect.y + trackRect.height / 2;
                g2.setColor(BORDER);
                g2.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.drawLine(trackRect.x, cy, trackRect.x + trackRect.width, cy);
                int filled = thumbRect.x + thumbRect.width / 2 - trackRect.x;
                g2.setColor(ACCENT);
                g2.drawLine(trackRect.x, cy, trackRect.x + filled, cy);
                g2.dispose();
            }
        });
        Hashtable<Integer,JLabel> labels = new Hashtable<>();
        for (int v : new int[]{0, 25, 50, 75, 100}) {
            JLabel l = new JLabel(Integer.toString(v));
            l.setForeground(TEXT_DIM);
            l.setFont(new Font("Monospaced", Font.PLAIN, 10));
            labels.put(v, l);
        }
        slider.setLabelTable(labels);
    }

    // ──────────────────────────────────────────────────────────────────────────
    //  Inner classes
    // ──────────────────────────────────────────────────────────────────────────

    private class SoundCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(
                JList<?> list, Object value, int index,
                boolean isSelected, boolean cellHasFocus) {

            JLabel lbl = (JLabel) super.getListCellRendererComponent(
                    list, value, index, isSelected, cellHasFocus);

            String name = value.toString();
            String icon = name.endsWith(".wav")  ? "◉ " :
                          name.endsWith(".aiff") ? "◈ " :
                          name.endsWith(".au")   ? "◆ " : "◎ ";

            lbl.setText(icon + name);
            lbl.setFont(new Font("Monospaced", Font.PLAIN, 13));
            lbl.setBorder(BorderFactory.createEmptyBorder(5, 6, 5, 6));

            if (isSelected) {
                lbl.setBackground(ACCENT_DIM);
                lbl.setForeground(ACCENT2);
            } else {
                lbl.setBackground(index % 2 == 0 ? SURFACE : SURFACE2);
                lbl.setForeground(TEXT);
            }
            return lbl;
        }
    }

    private static class ServerInfo {
        String ipAddress;
        int    port;
        String name;
    }
}
