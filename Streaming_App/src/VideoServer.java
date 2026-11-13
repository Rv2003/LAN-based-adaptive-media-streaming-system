import java.io.File;
import java.net.*;
import java.util.concurrent.*;

public class VideoServer {
    public static void main(String[] args) throws Exception {
        // Ensure the video storage directory exists
        File videoDir = new File(Protocol.VIDEO_DIR);
        if (!videoDir.exists()) {
            videoDir.mkdirs();
        }

        ExecutorService pool = Executors.newFixedThreadPool(10);
        try (ServerSocket server = new ServerSocket(Protocol.PORT)) {
            System.out.println("==================================================");
            System.out.println("🎬 Java Media Streaming Server Active!");
            System.out.println("Listening on Port: " + Protocol.PORT);
            System.out.println("Open in Browser to Watch: http://localhost:" + Protocol.PORT);
            System.out.println("Supported: Custom Socket (LIST, PLAY, RANGE, STOP) & HTTP 206 Streaming");
            System.out.println("==================================================");

            while (true) {
                Socket client = server.accept();
                pool.submit(new ClientHandler(client));
            }
        }
    }
}
