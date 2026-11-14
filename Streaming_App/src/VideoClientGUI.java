import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class VideoClientGUI extends JFrame {
    private JTextField hostField;
    private JTextField portField;
    private JComboBox<String> videoCombo;
    private JButton connectBtn;
    private JButton uploadBtn;
    private JButton streamBtn;
    private JButton openBrowserBtn;
    private JProgressBar progressBar;
    private JLabel statusLabel;
    private JLabel speedLabel;
    private JTextArea logArea;

    public VideoClientGUI() {
        setTitle("Java Media Streaming Client");
        setSize(700, 560);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout(10, 10));

        Font regularFont = new Font("Segoe UI", Font.PLAIN, 14);
        Font boldFont = new Font("Segoe UI", Font.BOLD, 14);

        JPanel topPanel = new JPanel(new GridLayout(2, 1, 6, 6));
        topPanel.setBorder(new EmptyBorder(12, 12, 0, 12));

        JPanel connPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        connPanel.add(new JLabel("Server Host:"));
        hostField = new JTextField("localhost", 10);
        connPanel.add(hostField);

        connPanel.add(new JLabel("Port:"));
        portField = new JTextField(String.valueOf(Protocol.PORT), 5);
        connPanel.add(portField);

        connectBtn = new JButton("Fetch Video List");
        connectBtn.setFont(boldFont);
        connectBtn.addActionListener(e -> fetchVideoList());
        connPanel.add(connectBtn);

        uploadBtn = new JButton("📤 Upload Video");
        uploadBtn.setFont(boldFont);
        uploadBtn.addActionListener(e -> uploadVideo());
        connPanel.add(uploadBtn);

        topPanel.add(connPanel);

        JPanel selectPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        selectPanel.add(new JLabel("Available Videos:"));
        videoCombo = new JComboBox<>();
        videoCombo.setPreferredSize(new Dimension(220, 28));
        selectPanel.add(videoCombo);

        streamBtn = new JButton("Stream & Play");
        streamBtn.setFont(boldFont);
        streamBtn.setBackground(new Color(37, 99, 235));
        streamBtn.setForeground(Color.WHITE);
        streamBtn.setEnabled(false);
        streamBtn.addActionListener(e -> startStreaming());
        selectPanel.add(streamBtn);

        openBrowserBtn = new JButton("Open Web Stream Player");
        openBrowserBtn.addActionListener(e -> openWebPlayer());
        selectPanel.add(openBrowserBtn);

        topPanel.add(selectPanel);
        add(topPanel, BorderLayout.NORTH);

        JPanel centerPanel = new JPanel(new BorderLayout(8, 8));
        centerPanel.setBorder(new EmptyBorder(6, 12, 6, 12));

        JPanel progContainer = new JPanel(new GridLayout(3, 1, 4, 4));
        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setFont(boldFont);
        progressBar.setPreferredSize(new Dimension(0, 26));

        statusLabel = new JLabel("Status: Ready. Click 'Fetch Video List' to connect.");
        statusLabel.setFont(regularFont);

        speedLabel = new JLabel("Speed: - | 0 / 0 MB");
        speedLabel.setFont(regularFont);

        progContainer.add(statusLabel);
        progContainer.add(progressBar);
        progContainer.add(speedLabel);

        centerPanel.add(progContainer, BorderLayout.NORTH);

        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setBackground(new Color(24, 24, 27));
        logArea.setForeground(new Color(34, 197, 94));
        logArea.setFont(new Font("Consolas", Font.PLAIN, 13));
        JScrollPane scrollPane = new JScrollPane(logArea);
        scrollPane.setBorder(BorderFactory.createTitledBorder("Socket Protocol Log"));
        centerPanel.add(scrollPane, BorderLayout.CENTER);

        add(centerPanel, BorderLayout.CENTER);

        SwingUtilities.invokeLater(this::fetchVideoList);
    }

    private void log(String msg) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(msg + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    private void fetchVideoList() {
        new Thread(() -> {
            String host = hostField.getText().trim();
            int port = Integer.parseInt(portField.getText().trim());

            try (Socket socket = new Socket(host, port);
                 BufferedInputStream bis = new BufferedInputStream(socket.getInputStream());
                 BufferedOutputStream bos = new BufferedOutputStream(socket.getOutputStream())) {

                log("--> Connected to " + host + ":" + port);
                log("--> Sent: LIST");
                bos.write("LIST\n".getBytes(StandardCharsets.US_ASCII));
                bos.flush();

                String countLine = readAsciiLine(bis);
                if (countLine == null) {
                    log("<-- No response from server");
                    return;
                }
                int count = Integer.parseInt(countLine.trim());
                log("<-- Server has " + count + " video(s)");

                DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
                for (int i = 0; i < count; i++) {
                    String fileLine = readAsciiLine(bis);
                    if (fileLine != null) {
                        String[] parts = fileLine.split(":");
                        String name = parts[0];
                        long size = parts.length > 1 ? Long.parseLong(parts[1]) : 0;
                        double sizeMb = size / (1024.0 * 1024.0);
                        model.addElement(name + String.format(" (%.2f MB)", sizeMb));
                    }
                }

                bos.write("STOP\n".getBytes(StandardCharsets.US_ASCII));
                bos.flush();

                SwingUtilities.invokeLater(() -> {
                    videoCombo.setModel(model);
                    streamBtn.setEnabled(model.getSize() > 0);
                    statusLabel.setText("Status: " + count + " video(s) found on server.");
                });

            } catch (Exception ex) {
                log("<-- Connection Error: " + ex.getMessage());
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Error connecting: Is VideoServer running on port " + port + "?");
                    JOptionPane.showMessageDialog(this,
                            "Could not connect to " + host + ":" + port + ".\nPlease start VideoServer first!",
                            "Connection Refused", JOptionPane.WARNING_MESSAGE);
                });
            }
        }).start();
    }

    private void uploadVideo() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select MP4 Video to Upload");
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("MP4 Video (*.mp4)", "mp4"));
        int res = chooser.showOpenDialog(this);
        if (res != JFileChooser.APPROVE_OPTION) return;

        File file = chooser.getSelectedFile();
        if (!file.exists() || !file.isFile()) return;

        uploadBtn.setEnabled(false);
        streamBtn.setEnabled(false);
        progressBar.setValue(0);

        new Thread(() -> {
            String host = hostField.getText().trim();
            int port = Integer.parseInt(portField.getText().trim());
            long totalSize = file.length();
            String fileName = file.getName();

            try (Socket socket = new Socket(host, port);
                 BufferedInputStream bis = new BufferedInputStream(socket.getInputStream());
                 BufferedOutputStream bos = new BufferedOutputStream(socket.getOutputStream());
                 FileInputStream fis = new FileInputStream(file)) {

                log("--> Sending: UPLOAD " + fileName + " (" + String.format("%.2f MB", totalSize / (1024.0 * 1024.0)) + ")");
                bos.write(("UPLOAD " + fileName + " " + totalSize + "\n").getBytes(StandardCharsets.US_ASCII));
                bos.flush();

                String ready = readAsciiLine(bis);
                if (ready == null || !ready.startsWith("READY")) {
                    log("<-- Server not ready: " + ready);
                    SwingUtilities.invokeLater(() -> {
                        statusLabel.setText("Server error: " + ready);
                        uploadBtn.setEnabled(true);
                    });
                    return;
                }

                byte[] buffer = new byte[Protocol.CHUNK_SIZE];
                long bytesSent = 0;
                long startTime = System.currentTimeMillis();

                while (bytesSent < totalSize) {
                    int toRead = (int) Math.min(buffer.length, totalSize - bytesSent);
                    int n = fis.read(buffer, 0, toRead);
                    if (n == -1) break;
                    bos.write(buffer, 0, n);
                    bytesSent += n;

                    int pct = (int) ((bytesSent * 100) / totalSize);
                    long elapsed = Math.max(1, System.currentTimeMillis() - startTime);
                    double speedMBs = (bytesSent / (1024.0 * 1024.0)) / (elapsed / 1000.0);
                    long finalBytes = bytesSent;

                    SwingUtilities.invokeLater(() -> {
                        progressBar.setValue(pct);
                        speedLabel.setText(String.format("Upload Speed: %.2f MB/s | %.2f / %.2f MB (%d%%)",
                                speedMBs, finalBytes / (1024.0 * 1024.0), totalSize / (1024.0 * 1024.0), pct));
                        statusLabel.setText("Uploading video chunks to server...");
                    });
                }
                bos.flush();

                String status = readAsciiLine(bis);
                log("<-- " + status);
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Upload complete! Refreshing video list...");
                    uploadBtn.setEnabled(true);
                    fetchVideoList();
                });

            } catch (Exception ex) {
                log("<-- Upload Error: " + ex.getMessage());
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Upload interrupted: " + ex.getMessage());
                    uploadBtn.setEnabled(true);
                });
            }
        }).start();
    }

    private void startStreaming() {
        String selected = (String) videoCombo.getSelectedItem();
        if (selected == null) return;
        String videoName = selected.split(" ")[0].trim();

        streamBtn.setEnabled(false);
        progressBar.setValue(0);

        new Thread(() -> {
            String host = hostField.getText().trim();
            int port = Integer.parseInt(portField.getText().trim());

            File outputFile = new File("streamed_" + videoName);

            try (Socket socket = new Socket(host, port);
                 BufferedInputStream bis = new BufferedInputStream(socket.getInputStream());
                 BufferedOutputStream bos = new BufferedOutputStream(socket.getOutputStream());
                 FileOutputStream fos = new FileOutputStream(outputFile)) {

                log("--> Sending: PLAY " + videoName);
                bos.write(("PLAY " + videoName + "\n").getBytes(StandardCharsets.US_ASCII));
                bos.flush();

                String header = readAsciiLine(bis);
                if (header == null || !header.startsWith("OK ")) {
                    log("<-- Server error: " + header);
                    SwingUtilities.invokeLater(() -> {
                        statusLabel.setText("Server response: " + header);
                        streamBtn.setEnabled(true);
                    });
                    return;
                }

                long totalSize = Long.parseLong(header.substring(3).trim());
                log("<-- Streaming started! File size: " + String.format("%.2f MB", totalSize / (1024.0 * 1024.0)));

                byte[] buffer = new byte[Protocol.CHUNK_SIZE];
                long bytesReceived = 0;
                long startTime = System.currentTimeMillis();
                boolean launchedPlayer = false;

                while (bytesReceived < totalSize) {
                    int toRead = (int) Math.min(buffer.length, totalSize - bytesReceived);
                    int n = bis.read(buffer, 0, toRead);
                    if (n == -1) break;
                    fos.write(buffer, 0, n);
                    bytesReceived += n;

                    int pct = (int) ((bytesReceived * 100) / totalSize);
                    long elapsed = Math.max(1, System.currentTimeMillis() - startTime);
                    double speedMBs = (bytesReceived / (1024.0 * 1024.0)) / (elapsed / 1000.0);
                    long finalBytes = bytesReceived;

                    SwingUtilities.invokeLater(() -> {
                        progressBar.setValue(pct);
                        speedLabel.setText(String.format("Speed: %.2f MB/s | %.2f / %.2f MB (%d%%)",
                                speedMBs, finalBytes / (1024.0 * 1024.0), totalSize / (1024.0 * 1024.0), pct));
                        statusLabel.setText("Streaming video chunks over socket...");
                    });

                    if (!launchedPlayer && bytesReceived >= Math.min(512 * 1024, totalSize / 4)) {
                        launchedPlayer = true;
                        fos.flush();
                        log("--> Progressive Buffer Ready! Launching video player...");
                        launchMedia(outputFile);
                    }
                }

                bos.write("STOP\n".getBytes(StandardCharsets.US_ASCII));
                bos.flush();

                log("<-- Streaming Complete! Saved to: " + outputFile.getName());
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Stream complete! Saved to " + outputFile.getName());
                    streamBtn.setEnabled(true);
                });

            } catch (Exception ex) {
                log("<-- Error during stream: " + ex.getMessage());
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("Stream interrupted: " + ex.getMessage());
                    streamBtn.setEnabled(true);
                });
            }
        }).start();
    }

    private void launchMedia(File file) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(file);
            }
        } catch (Exception ex) {
            log("--> Player notice: " + ex.getMessage());
        }
    }

    private void openWebPlayer() {
        try {
            String host = hostField.getText().trim();
            String port = portField.getText().trim();
            String url = "http://" + host + ":" + port;
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new java.net.URI(url));
            }
        } catch (Exception ex) {
            log("<-- Could not open browser: " + ex.getMessage());
        }
    }

    private static String readAsciiLine(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') baos.write(b);
        }
        return (baos.size() == 0 && b == -1) ? null : baos.toString(StandardCharsets.UTF_8);
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}

        SwingUtilities.invokeLater(() -> {
            VideoClientGUI gui = new VideoClientGUI();
            gui.setVisible(true);
        });
    }
}
