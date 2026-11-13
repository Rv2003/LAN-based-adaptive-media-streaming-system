import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class ClientHandler implements Runnable {
    private final Socket socket;

    public ClientHandler(Socket socket) {
        this.socket = socket;
    }

    @Override
    public void run() {
        try (socket;
             BufferedInputStream bis = new BufferedInputStream(socket.getInputStream());
             BufferedOutputStream bos = new BufferedOutputStream(socket.getOutputStream())) {

            while (true) {
                String line = readLine(bis);
                if (line == null) {
                    break; // Client closed connection
                }
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                // ==========================================
                // 1. HTTP STREAMING REQUEST (Browser / Media Player)
                // ==========================================
                if (line.startsWith("GET ")) {
                    handleHttpRequest(line, bis, bos);
                    break; // HTTP request served
                }

                // ==========================================
                // 2. CUSTOM SOCKET PROTOCOL: "LIST"
                // ==========================================
                if (line.equals("LIST")) {
                    File dir = new File(Protocol.VIDEO_DIR);
                    File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".mp4"));
                    StringBuilder sb = new StringBuilder();
                    int count = 0;
                    if (files != null) {
                        for (File f : files) {
                            sb.append(f.getName()).append(":").append(f.length()).append("\n");
                            count++;
                        }
                    }
                    writeAscii(bos, count + "\n" + sb);
                    bos.flush();
                }

                // ==========================================
                // 3. CUSTOM SOCKET PROTOCOL: "PLAY <filename>"
                // ==========================================
                else if (line.startsWith("PLAY ")) {
                    String name = new File(line.substring(5).trim()).getName(); // Prevent path traversal
                    File video = new File(Protocol.VIDEO_DIR, name);
                    if (!video.exists() || !video.isFile()) {
                        writeAscii(bos, "ERROR File not found\n");
                        bos.flush();
                        continue;
                    }

                    long fileSize = video.length();
                    writeAscii(bos, "OK " + fileSize + "\n");
                    bos.flush();

                    // Stream file in Protocol.CHUNK_SIZE chunks
                    try (FileInputStream fis = new FileInputStream(video)) {
                        byte[] buf = new byte[Protocol.CHUNK_SIZE];
                        int n;
                        while ((n = fis.read(buf)) != -1) {
                            bos.write(buf, 0, n);
                        }
                    }
                    bos.flush();
                }

                // ==========================================
                // 4. CUSTOM SOCKET PROTOCOL: "RANGE <file> <start> [length]"
                // Allows client to seek directly to any byte position!
                // ==========================================
                else if (line.startsWith("RANGE ")) {
                    String[] parts = line.substring(6).trim().split("\\s+");
                    String name = new File(parts[0]).getName();
                    long start = parts.length > 1 ? Long.parseLong(parts[1]) : 0;
                    File video = new File(Protocol.VIDEO_DIR, name);

                    if (!video.exists() || !video.isFile()) {
                        writeAscii(bos, "ERROR File not found\n");
                        bos.flush();
                        continue;
                    }

                    long fileSize = video.length();
                    long length = (parts.length > 2) ? Long.parseLong(parts[2]) : (fileSize - start);
                    length = Math.min(length, fileSize - start);

                    writeAscii(bos, "OK " + length + "\n");
                    bos.flush();

                    try (RandomAccessFile raf = new RandomAccessFile(video, "r")) {
                        raf.seek(start);
                        byte[] buf = new byte[Protocol.CHUNK_SIZE];
                        long remaining = length;
                        while (remaining > 0) {
                            int toRead = (int) Math.min(buf.length, remaining);
                            int n = raf.read(buf, 0, toRead);
                            if (n == -1) break;
                            bos.write(buf, 0, n);
                            remaining -= n;
                        }
                    }
                    bos.flush();
                }

                // ==========================================
                // 5. CUSTOM SOCKET PROTOCOL: "STOP"
                // ==========================================
                else if (line.equals("STOP")) {
                    break;
                }
            }
        } catch (IOException e) {
            // Client disconnect or closed stream
        }
    }

    /**
     * Handles HTTP 1.1 Range requests for real-time video streaming in web browsers / media players.
     */
    private void handleHttpRequest(String getLine, InputStream is, OutputStream os) throws IOException {
        String[] parts = getLine.split("\\s+");
        String uri = parts.length > 1 ? parts[1] : "/";

        // Read remaining HTTP request headers
        long rangeStart = 0;
        long rangeEnd = -1;
        boolean hasRange = false;

        String headerLine;
        while ((headerLine = readLine(is)) != null && !headerLine.trim().isEmpty()) {
            if (headerLine.toLowerCase().startsWith("range: bytes=")) {
                hasRange = true;
                String rangeVal = headerLine.substring(13).trim();
                String[] rParts = rangeVal.split("-");
                if (!rParts[0].isEmpty()) {
                    rangeStart = Long.parseLong(rParts[0]);
                }
                if (rParts.length > 1 && !rParts[1].isEmpty()) {
                    rangeEnd = Long.parseLong(rParts[1]);
                }
            }
        }

        // Serve HTML Web Player at "/"
        if (uri.equals("/") || uri.equals("/index.html")) {
            serveWebPlayer(os);
            return;
        }

        // Serve video stream at "/videos/<filename>"
        if (uri.startsWith("/videos/")) {
            String rawName = uri.substring(8);
            String name = new File(java.net.URLDecoder.decode(rawName, StandardCharsets.UTF_8)).getName();
            File video = new File(Protocol.VIDEO_DIR, name);

            if (!video.exists() || !video.isFile()) {
                String notFound = "HTTP/1.1 404 Not Found\r\nContent-Length: 9\r\n\r\nNot Found";
                writeAscii(os, notFound);
                os.flush();
                return;
            }

            long totalLength = video.length();
            if (rangeEnd == -1 || rangeEnd >= totalLength) {
                rangeEnd = totalLength - 1;
            }
            long contentLength = rangeEnd - rangeStart + 1;

            if (hasRange) {
                String headers = "HTTP/1.1 206 Partial Content\r\n" +
                        "Content-Type: video/mp4\r\n" +
                        "Accept-Ranges: bytes\r\n" +
                        "Content-Range: bytes " + rangeStart + "-" + rangeEnd + "/" + totalLength + "\r\n" +
                        "Content-Length: " + contentLength + "\r\n" +
                        "Connection: keep-alive\r\n\r\n";
                writeAscii(os, headers);
            } else {
                String headers = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: video/mp4\r\n" +
                        "Accept-Ranges: bytes\r\n" +
                        "Content-Length: " + totalLength + "\r\n" +
                        "Connection: keep-alive\r\n\r\n";
                writeAscii(os, headers);
            }
            os.flush();

            // Stream byte range from disk
            try (RandomAccessFile raf = new RandomAccessFile(video, "r")) {
                raf.seek(rangeStart);
                byte[] buf = new byte[Protocol.CHUNK_SIZE];
                long remaining = contentLength;
                while (remaining > 0) {
                    int toRead = (int) Math.min(buf.length, remaining);
                    int n = raf.read(buf, 0, toRead);
                    if (n == -1) break;
                    os.write(buf, 0, n);
                    remaining -= n;
                }
            }
            os.flush();
        }
    }

    /**
     * Serves an interactive HTML5 web player with live seek & stream capabilities.
     */
    private void serveWebPlayer(OutputStream os) throws IOException {
        File dir = new File(Protocol.VIDEO_DIR);
        File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".mp4"));

        StringBuilder listHtml = new StringBuilder();
        String defaultVideo = "";
        if (files != null && files.length > 0) {
            defaultVideo = files[0].getName();
            for (File f : files) {
                double sizeMb = f.length() / (1024.0 * 1024.0);
                listHtml.append(String.format(
                    "<option value='%s'>%s (%.2f MB)</option>",
                    f.getName(), f.getName(), sizeMb
                ));
            }
        }

        String html = "<!DOCTYPE html>\n" +
                "<html><head><meta charset='UTF-8'><title>Java Media Streaming Server</title>\n" +
                "<style>\n" +
                "body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #0f172a; color: #f8fafc; margin: 0; padding: 30px; display: flex; flex-direction: column; align-items: center; }\n" +
                ".container { max-width: 900px; width: 100%; background: #1e293b; border-radius: 12px; padding: 24px; box-shadow: 0 8px 30px rgba(0,0,0,0.4); }\n" +
                "h1 { margin-top: 0; font-size: 24px; color: #38bdf8; display: flex; align-items: center; gap: 10px; }\n" +
                ".badge { background: #0284c7; color: white; font-size: 12px; padding: 4px 8px; border-radius: 6px; }\n" +
                "select { width: 100%; padding: 12px; font-size: 15px; border-radius: 8px; border: 1px solid #475569; background: #0f172a; color: white; margin-bottom: 20px; outline: none; }\n" +
                "video { width: 100%; max-height: 520px; border-radius: 8px; background: black; box-shadow: 0 4px 16px rgba(0,0,0,0.5); }\n" +
                ".info { margin-top: 15px; padding: 12px; background: #334155; border-radius: 8px; font-size: 14px; color: #cbd5e1; }\n" +
                "</style></head><body>\n" +
                "<div class='container'>\n" +
                "  <h1>🎬 Java Media Streaming Server <span class='badge'>Live HTTP 206 Partial Content</span></h1>\n" +
                "  <label for='vSelect' style='font-size:14px; margin-bottom:6px; display:block;'>Select Video Stream:</label>\n" +
                "  <select id='vSelect' onchange='changeVideo(this.value)'>\n" +
                listHtml +
                "  </select>\n" +
                "  <video id='player' controls autoplay preload='auto'>\n" +
                "    <source id='vSource' src='/videos/" + defaultVideo + "' type='video/mp4'>\n" +
                "    Your browser does not support HTML5 video.\n" +
                "  </video>\n" +
                "  <div class='info'>\n" +
                "    <b>Real-time Media Streaming Active:</b> Video and audio play directly over HTTP Range byte-chunks. You can seek/scrub forward and backward instantly without downloading the whole file.\n" +
                "  </div>\n" +
                "</div>\n" +
                "<script>\n" +
                "function changeVideo(name) {\n" +
                "  var player = document.getElementById('player');\n" +
                "  var source = document.getElementById('vSource');\n" +
                "  source.src = '/videos/' + encodeURIComponent(name);\n" +
                "  player.load();\n" +
                "  player.play();\n" +
                "}\n" +
                "</script>\n" +
                "</body></html>";

        byte[] htmlBytes = html.getBytes(StandardCharsets.UTF_8);
        String header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=UTF-8\r\n" +
                "Content-Length: " + htmlBytes.length + "\r\n" +
                "Connection: close\r\n\r\n";
        writeAscii(os, header);
        os.write(htmlBytes);
        os.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') baos.write(b);
        }
        return (baos.size() == 0 && b == -1) ? null : baos.toString(StandardCharsets.UTF_8);
    }

    private static void writeAscii(OutputStream os, String text) throws IOException {
        os.write(text.getBytes(StandardCharsets.US_ASCII));
    }
}
