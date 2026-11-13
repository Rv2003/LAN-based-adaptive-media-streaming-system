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
                // 1. HTTP STREAMING & UPLOAD (Browser / Media Player)
                // ==========================================
                if (line.startsWith("GET ") || line.startsWith("POST ")) {
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
                // 5. CUSTOM SOCKET PROTOCOL: "UPLOAD <filename> <size>"
                // ==========================================
                else if (line.startsWith("UPLOAD ")) {
                    String[] parts = line.substring(7).trim().split("\\s+");
                    if (parts.length < 2) {
                        writeAscii(bos, "ERROR Usage: UPLOAD <filename> <size>\n");
                        bos.flush();
                        continue;
                    }
                    String name = new File(parts[0]).getName();
                    if (!name.toLowerCase().endsWith(".mp4")) {
                        name += ".mp4";
                    }
                    long size = Long.parseLong(parts[1]);
                    File videoDir = new File(Protocol.VIDEO_DIR);
                    if (!videoDir.exists()) videoDir.mkdirs();
                    File dest = new File(videoDir, name);

                    writeAscii(bos, "READY\n");
                    bos.flush();

                    try (FileOutputStream fos = new FileOutputStream(dest)) {
                        byte[] buf = new byte[Protocol.CHUNK_SIZE];
                        long remaining = size;
                        while (remaining > 0) {
                            int toRead = (int) Math.min(buf.length, remaining);
                            int n = bis.read(buf, 0, toRead);
                            if (n == -1) break;
                            fos.write(buf, 0, n);
                            remaining -= n;
                        }
                    }
                    writeAscii(bos, "OK Upload complete\n");
                    bos.flush();
                }

                // ==========================================
                // 6. CUSTOM SOCKET PROTOCOL: "STOP"
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

        // Handle video upload at "/upload"
        if (uri.startsWith("/upload")) {
            handleFileUpload(uri, is, os);
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
     * Handles binary video upload from web browsers or HTTP clients.
     */
    private void handleFileUpload(String uri, InputStream is, OutputStream os) throws IOException {
        long contentLength = 0;
        String headerLine;
        while ((headerLine = readLine(is)) != null && !headerLine.trim().isEmpty()) {
            if (headerLine.toLowerCase().startsWith("content-length:")) {
                try {
                    contentLength = Long.parseLong(headerLine.substring(15).trim());
                } catch (NumberFormatException ignored) {}
            }
        }

        String fileName = "video_" + System.currentTimeMillis() + ".mp4";
        int qIdx = uri.indexOf("name=");
        if (qIdx != -1) {
            String raw = uri.substring(qIdx + 5);
            int amp = raw.indexOf('&');
            if (amp != -1) raw = raw.substring(0, amp);
            raw = java.net.URLDecoder.decode(raw, StandardCharsets.UTF_8);
            fileName = new File(raw).getName();
        }
        if (!fileName.toLowerCase().endsWith(".mp4")) {
            fileName += ".mp4";
        }

        File videoDir = new File(Protocol.VIDEO_DIR);
        if (!videoDir.exists()) videoDir.mkdirs();
        File destFile = new File(videoDir, fileName);

        try (FileOutputStream fos = new FileOutputStream(destFile)) {
            byte[] buf = new byte[Protocol.CHUNK_SIZE];
            long remaining = contentLength;
            while (remaining > 0) {
                int toRead = (int) Math.min(buf.length, remaining);
                int n = is.read(buf, 0, toRead);
                if (n == -1) break;
                fos.write(buf, 0, n);
                remaining -= n;
            }
        }

        String responseBody = "{\"status\":\"success\",\"name\":\"" + fileName + "\"}";
        byte[] bodyBytes = responseBody.getBytes(StandardCharsets.UTF_8);
        String response = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Content-Length: " + bodyBytes.length + "\r\n" +
                "Connection: close\r\n\r\n";
        writeAscii(os, response);
        os.write(bodyBytes);
        os.flush();
    }

    /**
     * Serves an interactive HTML5 web player with live seek & stream capabilities and video uploader.
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
                "<html><head><meta charset='UTF-8'><title>LAN Media Streaming Server</title>\n" +
                "<style>\n" +
                "body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #0f172a; color: #f8fafc; margin: 0; padding: 24px; display: flex; flex-direction: column; align-items: center; }\n" +
                ".container { max-width: 900px; width: 100%; background: #1e293b; border-radius: 12px; padding: 24px; box-shadow: 0 8px 30px rgba(0,0,0,0.4); }\n" +
                ".header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }\n" +
                "h1 { margin: 0; font-size: 22px; color: #38bdf8; display: flex; align-items: center; gap: 8px; }\n" +
                ".badge { background: #0284c7; color: white; font-size: 12px; padding: 4px 8px; border-radius: 6px; }\n" +
                ".badge-lan { background: #10b981; color: white; font-size: 12px; padding: 4px 8px; border-radius: 6px; }\n" +
                ".lan-box { background: #334155; border-left: 4px solid #10b981; padding: 10px 14px; border-radius: 6px; margin-bottom: 18px; font-size: 14px; }\n" +
                "select { width: 100%; padding: 10px; font-size: 15px; border-radius: 8px; border: 1px solid #475569; background: #0f172a; color: white; margin-bottom: 16px; outline: none; }\n" +
                "video { width: 100%; max-height: 480px; border-radius: 8px; background: black; box-shadow: 0 4px 16px rgba(0,0,0,0.5); }\n" +
                ".upload-box { margin-top: 20px; padding: 18px; background: #0f172a; border: 2px dashed #475569; border-radius: 10px; text-align: center; }\n" +
                ".upload-btn { background: #2563eb; color: white; border: none; padding: 10px 20px; font-size: 14px; font-weight: bold; border-radius: 6px; cursor: pointer; transition: 0.2s; }\n" +
                ".upload-btn:hover { background: #1d4ed8; }\n" +
                ".info { margin-top: 15px; padding: 12px; background: #334155; border-radius: 8px; font-size: 13px; color: #cbd5e1; }\n" +
                "progress { width: 100%; height: 16px; border-radius: 8px; margin-top: 10px; accent-color: #38bdf8; }\n" +
                "</style></head><body>\n" +
                "<div class='container'>\n" +
                "  <div class='header'>\n" +
                "    <h1>🎬 LAN Media Streaming System</h1>\n" +
                "    <div><span class='badge'>HTTP 206 Partial Content</span> <span class='badge-lan'>LAN Ready</span></div>\n" +
                "  </div>\n" +
                "  <div class='lan-box'>\n" +
                "    📡 <b>Laptop-to-Laptop Streaming:</b> Connect any other laptop or phone on this Wi-Fi network and open this page URL (e.g., <code>http://&lt;Host-IP&gt;:5050</code>) to watch or add videos!\n" +
                "  </div>\n" +
                "  <label for='vSelect' style='font-size:14px; margin-bottom:6px; display:block;'>Select Video to Stream:</label>\n" +
                "  <select id='vSelect' onchange='changeVideo(this.value)'>\n" +
                listHtml +
                "  </select>\n" +
                "  <video id='player' controls autoplay preload='auto'>\n" +
                "    <source id='vSource' src='/videos/" + defaultVideo + "' type='video/mp4'>\n" +
                "    Your browser does not support HTML5 video.\n" +
                "  </video>\n" +
                "  <div class='upload-box'>\n" +
                "    <h3 style='margin-top:0; font-size:16px; color:#38bdf8;'>📤 Add / Upload New Video</h3>\n" +
                "    <p style='font-size:13px; color:#94a3b8; margin-bottom:12px;'>Upload an MP4 file from this device to stream across your local network:</p>\n" +
                "    <input type='file' id='fileInput' accept='video/mp4' style='display:none' onchange='uploadFile(this.files[0])'>\n" +
                "    <button type='button' class='upload-btn' onclick=\"document.getElementById('fileInput').click()\">Choose MP4 Video to Upload</button>\n" +
                "    <div id='uploadStatus' style='font-size:14px; margin-top:10px; color:#38bdf8;'></div>\n" +
                "    <div id='progressContainer' style='display:none; margin-top:10px;'>\n" +
                "      <progress id='uploadProgress' value='0' max='100'></progress>\n" +
                "    </div>\n" +
                "  </div>\n" +
                "  <div class='info'>\n" +
                "    <b>Real-time Media Streaming Active:</b> Chunks stream dynamically over TCP/HTTP Range requests with instant scrub/seek.\n" +
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
                "function uploadFile(file) {\n" +
                "  if (!file) return;\n" +
                "  if (!file.name.toLowerCase().endsWith('.mp4')) {\n" +
                "    alert('Please select an .mp4 file.');\n" +
                "    return;\n" +
                "  }\n" +
                "  var status = document.getElementById('uploadStatus');\n" +
                "  var pContainer = document.getElementById('progressContainer');\n" +
                "  var prog = document.getElementById('uploadProgress');\n" +
                "  pContainer.style.display = 'block';\n" +
                "  prog.value = 0;\n" +
                "  status.innerText = 'Uploading ' + file.name + ' (' + (file.size / (1024*1024)).toFixed(2) + ' MB)...';\n" +
                "  var xhr = new XMLHttpRequest();\n" +
                "  xhr.open('POST', '/upload?name=' + encodeURIComponent(file.name), true);\n" +
                "  xhr.upload.onprogress = function(e) {\n" +
                "    if (e.lengthComputable) {\n" +
                "      var pct = Math.round((e.loaded / e.total) * 100);\n" +
                "      prog.value = pct;\n" +
                "      status.innerText = 'Uploading ' + file.name + ': ' + pct + '% (' + (e.loaded / (1024*1024)).toFixed(1) + ' / ' + (e.total / (1024*1024)).toFixed(1) + ' MB)';\n" +
                "    }\n" +
                "  };\n" +
                "  xhr.onload = function() {\n" +
                "    if (xhr.status === 200) {\n" +
                "      status.innerText = '✅ Upload successful! Reloading playlist...';\n" +
                "      setTimeout(function() { window.location.reload(); }, 1200);\n" +
                "    } else {\n" +
                "      status.innerText = '❌ Upload failed (HTTP ' + xhr.status + ')';\n" +
                "    }\n" +
                "  };\n" +
                "  xhr.onerror = function() {\n" +
                "    status.innerText = '❌ Network error during upload.';\n" +
                "  };\n" +
                "  xhr.send(file);\n" +
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
