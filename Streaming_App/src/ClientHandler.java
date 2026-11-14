import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;

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
                    break;
                }
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                String method = line.split("\\s+")[0].toUpperCase();
                if (method.equals("GET") || method.equals("POST") || method.equals("HEAD") || method.equals("OPTIONS")) {
                    handleHttpRequest(line, method, bis, bos);
                    break;
                }

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
                } else if (line.startsWith("PLAY ")) {
                    String name = new File(line.substring(5).trim()).getName();
                    File video = new File(Protocol.VIDEO_DIR, name);
                    if (!video.exists() || !video.isFile()) {
                        writeAscii(bos, "ERROR File not found\n");
                        bos.flush();
                        continue;
                    }

                    long fileSize = video.length();
                    writeAscii(bos, "OK " + fileSize + "\n");
                    bos.flush();

                    try (FileInputStream fis = new FileInputStream(video)) {
                        byte[] buf = new byte[Protocol.CHUNK_SIZE];
                        int n;
                        while ((n = fis.read(buf)) != -1) {
                            bos.write(buf, 0, n);
                        }
                    }
                    bos.flush();
                } else if (line.startsWith("RANGE ")) {
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
                } else if (line.startsWith("UPLOAD ")) {
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
                } else if (line.equals("STOP")) {
                    break;
                }
            }
        } catch (IOException ignored) {
        }
    }

    private void handleHttpRequest(String getLine, String method, InputStream is, OutputStream os) throws IOException {
        String[] parts = getLine.split("\\s+");
        String uri = parts.length > 1 ? parts[1] : "/";

        long rangeStart = 0;
        long rangeEnd = -1;
        boolean hasRange = false;
        long contentLength = 0;

        String headerLine;
        while ((headerLine = readLine(is)) != null && !headerLine.trim().isEmpty()) {
            String lower = headerLine.toLowerCase();
            if (lower.startsWith("range: bytes=")) {
                hasRange = true;
                String rangeVal = headerLine.substring(13).trim();
                String[] rParts = rangeVal.split("-");
                if (!rParts[0].isEmpty()) {
                    try { rangeStart = Long.parseLong(rParts[0]); } catch (NumberFormatException ignored) {}
                }
                if (rParts.length > 1 && !rParts[1].isEmpty()) {
                    try { rangeEnd = Long.parseLong(rParts[1]); } catch (NumberFormatException ignored) {}
                }
            } else if (lower.startsWith("content-length:")) {
                try {
                    contentLength = Long.parseLong(headerLine.substring(15).trim());
                } catch (NumberFormatException ignored) {}
            }
        }

        if (method.equals("OPTIONS")) {
            writeAscii(os, "HTTP/1.1 204 No Content\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Access-Control-Allow-Methods: GET, POST, HEAD, OPTIONS\r\n" +
                    "Access-Control-Allow-Headers: Range, Content-Type, Content-Length\r\n" +
                    "Connection: close\r\n\r\n");
            os.flush();
            return;
        }

        if (uri.equals("/") || uri.equals("/index.html")) {
            serveWebPlayer(os);
            return;
        }

        if (uri.startsWith("/upload")) {
            handleFileUpload(uri, contentLength, is, os);
            return;
        }

        if (uri.startsWith("/videos/")) {
            String rawName = uri.substring(8);
            String name = new File(java.net.URLDecoder.decode(rawName, StandardCharsets.UTF_8)).getName();
            File video = new File(Protocol.VIDEO_DIR, name);

            if (!video.exists() || !video.isFile()) {
                String notFound = "HTTP/1.1 404 Not Found\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
                writeAscii(os, notFound);
                os.flush();
                return;
            }

            long totalLength = video.length();
            if (rangeEnd == -1 || rangeEnd >= totalLength) {
                rangeEnd = totalLength - 1;
            }
            long videoContentLength = rangeEnd - rangeStart + 1;

            if (hasRange) {
                String headers = "HTTP/1.1 206 Partial Content\r\n" +
                        "Content-Type: video/mp4\r\n" +
                        "Accept-Ranges: bytes\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Content-Range: bytes " + rangeStart + "-" + rangeEnd + "/" + totalLength + "\r\n" +
                        "Content-Length: " + videoContentLength + "\r\n" +
                        "Connection: close\r\n\r\n";
                writeAscii(os, headers);
            } else {
                String headers = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: video/mp4\r\n" +
                        "Accept-Ranges: bytes\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Content-Length: " + totalLength + "\r\n" +
                        "Connection: close\r\n\r\n";
                writeAscii(os, headers);
            }
            os.flush();

            if (method.equals("HEAD")) {
                return;
            }

            try (RandomAccessFile raf = new RandomAccessFile(video, "r")) {
                raf.seek(rangeStart);
                byte[] buf = new byte[Protocol.CHUNK_SIZE];
                long remaining = videoContentLength;
                while (remaining > 0) {
                    int toRead = (int) Math.min(buf.length, remaining);
                    int n = raf.read(buf, 0, toRead);
                    if (n == -1) break;
                    os.write(buf, 0, n);
                    remaining -= n;
                }
            }
            os.flush();
            return;
        }

        String notFound = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
        writeAscii(os, notFound);
        os.flush();
    }

    private void handleFileUpload(String uri, long contentLength, InputStream is, OutputStream os) throws IOException {
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

    private void serveWebPlayer(OutputStream os) throws IOException {
        File dir = new File(Protocol.VIDEO_DIR);
        File[] rawFiles = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".mp4"));

        List<File> files = new ArrayList<>();
        if (rawFiles != null) {
            for (File f : rawFiles) {
                if (f.length() > 1024) {
                    files.add(0, f);
                } else {
                    files.add(f);
                }
            }
        }

        StringBuilder listHtml = new StringBuilder();
        String defaultVideo = "";
        if (!files.isEmpty()) {
            defaultVideo = files.get(0).getName();
            for (File f : files) {
                double sizeMb = f.length() / (1024.0 * 1024.0);
                listHtml.append(String.format(
                    "<option value='%s'>%s (%.2f MB)</option>",
                    f.getName(), f.getName(), sizeMb
                ));
            }
        }

        String html = "<!DOCTYPE html>\n" +
                "<html lang='en'>\n" +
                "<head>\n" +
                "<meta charset='UTF-8'>\n" +
                "<meta name='viewport' content='width=device-width, initial-scale=1.0'>\n" +
                "<title>StreamVibe • LAN Media Hub</title>\n" +
                "<style>\n" +
                "* { box-sizing: border-box; margin: 0; padding: 0; }\n" +
                ":root {\n" +
                "  --bg: #090d16;\n" +
                "  --panel: rgba(17, 24, 39, 0.78);\n" +
                "  --border: rgba(255, 255, 255, 0.08);\n" +
                "  --primary: #6366f1;\n" +
                "  --primary-glow: rgba(99, 102, 241, 0.35);\n" +
                "  --accent-grad: linear-gradient(135deg, #6366f1 0%, #a855f7 100%);\n" +
                "  --text: #f8fafc;\n" +
                "  --text-dim: #94a3b8;\n" +
                "  --success: #10b981;\n" +
                "}\n" +
                "body {\n" +
                "  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif;\n" +
                "  background-color: var(--bg);\n" +
                "  background-image: radial-gradient(circle at 10% 15%, rgba(99, 102, 241, 0.15), transparent 45%), radial-gradient(circle at 90% 85%, rgba(168, 85, 247, 0.14), transparent 45%);\n" +
                "  background-attachment: fixed;\n" +
                "  color: var(--text);\n" +
                "  min-height: 100vh;\n" +
                "  padding: 20px 14px;\n" +
                "  display: flex;\n" +
                "  flex-direction: column;\n" +
                "  align-items: center;\n" +
                "}\n" +
                ".shell {\n" +
                "  width: 100%;\n" +
                "  max-width: 920px;\n" +
                "  display: flex;\n" +
                "  flex-direction: column;\n" +
                "  gap: 18px;\n" +
                "}\n" +
                ".panel {\n" +
                "  background: var(--panel);\n" +
                "  backdrop-filter: blur(20px);\n" +
                "  -webkit-backdrop-filter: blur(20px);\n" +
                "  border: 1px solid var(--border);\n" +
                "  border-radius: 18px;\n" +
                "  box-shadow: 0 20px 40px -15px rgba(0, 0, 0, 0.7);\n" +
                "  padding: 22px;\n" +
                "}\n" +
                ".header {\n" +
                "  display: flex;\n" +
                "  flex-wrap: wrap;\n" +
                "  justify-content: space-between;\n" +
                "  align-items: center;\n" +
                "  gap: 12px;\n" +
                "}\n" +
                ".brand {\n" +
                "  display: flex;\n" +
                "  align-items: center;\n" +
                "  gap: 12px;\n" +
                "}\n" +
                ".brand-icon {\n" +
                "  width: 40px;\n" +
                "  height: 40px;\n" +
                "  border-radius: 12px;\n" +
                "  background: var(--accent-grad);\n" +
                "  display: flex;\n" +
                "  align-items: center;\n" +
                "  justify-content: center;\n" +
                "  font-size: 20px;\n" +
                "  box-shadow: 0 4px 16px var(--primary-glow);\n" +
                "}\n" +
                ".brand-name {\n" +
                "  font-size: 22px;\n" +
                "  font-weight: 700;\n" +
                "  letter-spacing: -0.5px;\n" +
                "  background: linear-gradient(135deg, #ffffff 0%, #cbd5e1 100%);\n" +
                "  -webkit-background-clip: text;\n" +
                "  -webkit-text-fill-color: transparent;\n" +
                "}\n" +
                ".brand-sub {\n" +
                "  font-size: 12px;\n" +
                "  color: var(--text-dim);\n" +
                "}\n" +
                ".pills {\n" +
                "  display: flex;\n" +
                "  align-items: center;\n" +
                "  gap: 8px;\n" +
                "  flex-wrap: wrap;\n" +
                "}\n" +
                ".pill {\n" +
                "  display: inline-flex;\n" +
                "  align-items: center;\n" +
                "  gap: 6px;\n" +
                "  font-size: 12px;\n" +
                "  font-weight: 600;\n" +
                "  padding: 5px 12px;\n" +
                "  border-radius: 9999px;\n" +
                "}\n" +
                ".pill-lan {\n" +
                "  background: rgba(16, 185, 129, 0.12);\n" +
                "  border: 1px solid rgba(16, 185, 129, 0.3);\n" +
                "  color: #34d399;\n" +
                "}\n" +
                ".pill-byte {\n" +
                "  background: rgba(99, 102, 241, 0.12);\n" +
                "  border: 1px solid rgba(99, 102, 241, 0.3);\n" +
                "  color: #a5b4fc;\n" +
                "}\n" +
                ".pulse {\n" +
                "  width: 7px;\n" +
                "  height: 7px;\n" +
                "  border-radius: 50%;\n" +
                "  background: #10b981;\n" +
                "  box-shadow: 0 0 0 0 rgba(16, 185, 129, 0.7);\n" +
                "  animation: pulse 2s infinite;\n" +
                "}\n" +
                "@keyframes pulse {\n" +
                "  0% { box-shadow: 0 0 0 0 rgba(16, 185, 129, 0.7); }\n" +
                "  70% { box-shadow: 0 0 0 6px rgba(16, 185, 129, 0); }\n" +
                "  100% { box-shadow: 0 0 0 0 rgba(16, 185, 129, 0); }\n" +
                "}\n" +
                ".video-viewport {\n" +
                "  border-radius: 14px;\n" +
                "  overflow: hidden;\n" +
                "  background: #000000;\n" +
                "  box-shadow: 0 16px 36px -10px rgba(0, 0, 0, 0.9), 0 0 24px -6px var(--primary-glow);\n" +
                "  border: 1px solid rgba(255, 255, 255, 0.1);\n" +
                "}\n" +
                "video {\n" +
                "  width: 100%;\n" +
                "  max-height: 480px;\n" +
                "  display: block;\n" +
                "  outline: none;\n" +
                "}\n" +
                ".select-bar {\n" +
                "  display: flex;\n" +
                "  flex-direction: column;\n" +
                "  gap: 8px;\n" +
                "}\n" +
                ".select-label {\n" +
                "  font-size: 13px;\n" +
                "  font-weight: 600;\n" +
                "  color: var(--text-dim);\n" +
                "  display: flex;\n" +
                "  justify-content: space-between;\n" +
                "}\n" +
                ".select-box {\n" +
                "  width: 100%;\n" +
                "  padding: 12px 16px;\n" +
                "  font-size: 15px;\n" +
                "  font-weight: 500;\n" +
                "  border-radius: 12px;\n" +
                "  border: 1px solid rgba(255, 255, 255, 0.12);\n" +
                "  background: rgba(15, 23, 42, 0.85);\n" +
                "  color: #ffffff;\n" +
                "  outline: none;\n" +
                "  cursor: pointer;\n" +
                "  transition: 0.2s ease;\n" +
                "}\n" +
                ".select-box:focus, .select-box:hover {\n" +
                "  border-color: var(--primary);\n" +
                "  box-shadow: 0 0 0 3px rgba(99, 102, 241, 0.2);\n" +
                "}\n" +
                ".upload-drop {\n" +
                "  border: 2px dashed rgba(255, 255, 255, 0.14);\n" +
                "  border-radius: 14px;\n" +
                "  padding: 24px 16px;\n" +
                "  text-align: center;\n" +
                "  background: rgba(15, 23, 42, 0.4);\n" +
                "  display: flex;\n" +
                "  flex-direction: column;\n" +
                "  align-items: center;\n" +
                "  gap: 10px;\n" +
                "  cursor: pointer;\n" +
                "  transition: 0.2s ease;\n" +
                "}\n" +
                ".upload-drop:hover {\n" +
                "  border-color: var(--primary);\n" +
                "  background: rgba(99, 102, 241, 0.06);\n" +
                "}\n" +
                ".upload-btn {\n" +
                "  background: var(--accent-grad);\n" +
                "  color: white;\n" +
                "  border: none;\n" +
                "  padding: 10px 22px;\n" +
                "  font-size: 14px;\n" +
                "  font-weight: 600;\n" +
                "  border-radius: 10px;\n" +
                "  cursor: pointer;\n" +
                "  transition: 0.2s ease;\n" +
                "  box-shadow: 0 4px 14px var(--primary-glow);\n" +
                "}\n" +
                ".upload-btn:hover {\n" +
                "  transform: translateY(-1px);\n" +
                "  box-shadow: 0 6px 18px var(--primary-glow);\n" +
                "}\n" +
                ".progress-card {\n" +
                "  display: none;\n" +
                "  background: rgba(15, 23, 42, 0.6);\n" +
                "  border: 1px solid rgba(255, 255, 255, 0.08);\n" +
                "  border-radius: 10px;\n" +
                "  padding: 12px 14px;\n" +
                "  margin-top: 10px;\n" +
                "}\n" +
                ".progress-track {\n" +
                "  width: 100%;\n" +
                "  height: 8px;\n" +
                "  background: rgba(255, 255, 255, 0.08);\n" +
                "  border-radius: 9999px;\n" +
                "  overflow: hidden;\n" +
                "  margin-top: 8px;\n" +
                "}\n" +
                ".progress-bar {\n" +
                "  width: 0%;\n" +
                "  height: 100%;\n" +
                "  background: linear-gradient(90deg, #38bdf8, #818cf8, #c084fc);\n" +
                "  border-radius: 9999px;\n" +
                "  transition: width 0.15s ease;\n" +
                "}\n" +
                ".footer-text {\n" +
                "  text-align: center;\n" +
                "  font-size: 12px;\n" +
                "  color: var(--text-dim);\n" +
                "  margin-top: 4px;\n" +
                "}\n" +
                "</style>\n" +
                "</head>\n" +
                "<body>\n" +
                "<div class='shell'>\n" +
                "  <div class='panel'>\n" +
                "    <div class='header'>\n" +
                "      <div class='brand'>\n" +
                "        <div class='brand-icon'>🎬</div>\n" +
                "        <div>\n" +
                "          <div class='brand-name'>StreamVibe</div>\n" +
                "          <div class='brand-sub'>LAN Media Streaming Server</div>\n" +
                "        </div>\n" +
                "      </div>\n" +
                "      <div class='pills'>\n" +
                "        <div class='pill pill-lan'><span class='pulse'></span> LAN Connected</div>\n" +
                "        <div class='pill pill-byte'>HTTP 206 Partial Content</div>\n" +
                "      </div>\n" +
                "    </div>\n" +
                "  </div>\n" +
                "  <div class='panel'>\n" +
                "    <div class='select-bar'>\n" +
                "      <div class='select-label'>\n" +
                "        <span>Select Media to Stream</span>\n" +
                "        <span id='videoCount'>" + files.size() + " videos available</span>\n" +
                "      </div>\n" +
                "      <select id='vSelect' class='select-box' onchange='changeVideo(this.value)'>\n" +
                listHtml +
                "      </select>\n" +
                "    </div>\n" +
                "    <div class='video-viewport' style='margin-top: 14px;'>\n" +
                "      <video id='player' controls playsinline preload='metadata'>\n" +
                "        <source id='vSource' src='/videos/" + defaultVideo + "' type='video/mp4'>\n" +
                "        Your browser does not support HTML5 video.\n" +
                "      </video>\n" +
                "    </div>\n" +
                "  </div>\n" +
                "  <div class='panel'>\n" +
                "    <div class='upload-drop' onclick=\"document.getElementById('fileInput').click()\">\n" +
                "      <div style='font-size: 28px;'>📤</div>\n" +
                "      <div style='font-weight: 600; font-size: 15px;'>Upload New Video</div>\n" +
                "      <div style='font-size: 13px; color: var(--text-dim);'>Tap to select an MP4 video from this device</div>\n" +
                "      <input type='file' id='fileInput' accept='video/mp4,video/*' style='display:none' onchange='uploadFile(this.files[0])'>\n" +
                "      <button type='button' class='upload-btn'>Choose Video</button>\n" +
                "    </div>\n" +
                "    <div id='progressCard' class='progress-card'>\n" +
                "      <div style='display: flex; justify-content: space-between; font-size: 13px;'>\n" +
                "        <span id='uploadStatus'>Uploading...</span>\n" +
                "        <span id='uploadPct'>0%</span>\n" +
                "      </div>\n" +
                "      <div class='progress-track'>\n" +
                "        <div id='progressBar' class='progress-bar'></div>\n" +
                "      </div>\n" +
                "    </div>\n" +
                "  </div>\n" +
                "  <div class='footer-text'>StreamVibe Media Server • Instant Seeking & Progressive Buffering</div>\n" +
                "</div>\n" +
                "<script>\n" +
                "function changeVideo(name) {\n" +
                "  var player = document.getElementById('player');\n" +
                "  var source = document.getElementById('vSource');\n" +
                "  source.src = '/videos/' + encodeURIComponent(name);\n" +
                "  player.load();\n" +
                "  var p = player.play();\n" +
                "  if (p !== undefined) {\n" +
                "    p.catch(function(err) {});\n" +
                "  }\n" +
                "}\n" +
                "function uploadFile(file) {\n" +
                "  if (!file) return;\n" +
                "  var name = file.name || ('video_' + Date.now() + '.mp4');\n" +
                "  var status = document.getElementById('uploadStatus');\n" +
                "  var pctLabel = document.getElementById('uploadPct');\n" +
                "  var bar = document.getElementById('progressBar');\n" +
                "  var card = document.getElementById('progressCard');\n" +
                "  card.style.display = 'block';\n" +
                "  bar.style.width = '0%';\n" +
                "  status.innerText = 'Uploading ' + name + ' (' + (file.size / (1024*1024)).toFixed(2) + ' MB)...';\n" +
                "  var xhr = new XMLHttpRequest();\n" +
                "  xhr.open('POST', '/upload?name=' + encodeURIComponent(name), true);\n" +
                "  xhr.upload.onprogress = function(e) {\n" +
                "    if (e.lengthComputable) {\n" +
                "      var pct = Math.round((e.loaded / e.total) * 100);\n" +
                "      bar.style.width = pct + '%';\n" +
                "      pctLabel.innerText = pct + '%';\n" +
                "      status.innerText = 'Uploading ' + name + ': ' + (e.loaded / (1024*1024)).toFixed(1) + ' / ' + (e.total / (1024*1024)).toFixed(1) + ' MB';\n" +
                "    }\n" +
                "  };\n" +
                "  xhr.onload = function() {\n" +
                "    if (xhr.status === 200) {\n" +
                "      status.innerText = '✅ Upload successful! Updating playlist...';\n" +
                "      pctLabel.innerText = '100%';\n" +
                "      bar.style.width = '100%';\n" +
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
                "</body>\n" +
                "</html>";

        byte[] htmlBytes = html.getBytes(StandardCharsets.UTF_8);
        String header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=UTF-8\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
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
