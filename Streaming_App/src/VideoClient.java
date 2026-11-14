import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class VideoClient {
    public static void main(String[] args) throws Exception {
        try (Socket socket = new Socket("localhost", Protocol.PORT);
             BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {

            out.println("PLAY sheli.mp4");

            String header = readLine(in);
            if (header == null) {
                System.out.println("No response from server.");
                return;
            }

            if (!header.startsWith("OK ")) {
                System.out.println("Server error: " + header);
                return;
            }

            long size = Long.parseLong(header.substring(3).trim());
            System.out.println("Downloading video (" + size + " bytes)...");

            try (FileOutputStream fos = new FileOutputStream("received.mp4")) {
                byte[] buf = new byte[Protocol.CHUNK_SIZE];
                long remaining = size;
                while (remaining > 0) {
                    int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                    if (n == -1) break;
                    fos.write(buf, 0, n);
                    remaining -= n;
                }
            }

            out.println("STOP");
            System.out.println("Success! Received " + size + " bytes and saved to received.mp4");
        }
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
}
