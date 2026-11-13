import java.io.File;
import java.net.*;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.*;

public class VideoServer {
    public static void main(String[] args) throws Exception {
        // Ensure the video storage directory exists
        File videoDir = new File(Protocol.VIDEO_DIR);
        if (!videoDir.exists()) {
            videoDir.mkdirs();
        }

        List<String> lanIps = getLanAddresses();

        ExecutorService pool = Executors.newFixedThreadPool(10);
        try (ServerSocket server = new ServerSocket(Protocol.PORT)) {
            System.out.println("==================================================");
            System.out.println("🎬 Java Media Streaming Server Active!");
            System.out.println("Listening on Port: " + Protocol.PORT);
            System.out.println("💻 Local URL:      http://localhost:" + Protocol.PORT);
            if (!lanIps.isEmpty()) {
                System.out.println("📡 LAN Streaming URLs (Open on another laptop/phone):");
                for (String ip : lanIps) {
                    System.out.println("   👉 http://" + ip + ":" + Protocol.PORT);
                }
            } else {
                System.out.println("📡 LAN Streaming: Connect to same Wi-Fi and use your machine's IP:" + Protocol.PORT);
            }
            System.out.println("Supported: Web Browser (HTML5/Range), VideoClientGUI, VideoClient");
            System.out.println("Features: Multi-device streaming & Dynamic Video Upload");
            System.out.println("==================================================");

            while (true) {
                Socket client = server.accept();
                pool.submit(new ClientHandler(client));
            }
        }
    }

    /**
     * Discovers all active IPv4 LAN addresses across network interfaces (Wi-Fi, Ethernet).
     */
    public static List<String> getLanAddresses() {
        List<String> list = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp() || iface.isVirtual()) continue;
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        list.add(addr.getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {}
        return list;
    }
}
