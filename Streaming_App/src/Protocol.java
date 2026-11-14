import java.io.File;

public class Protocol {
    public static final int PORT = 5050;
    public static final int CHUNK_SIZE = 64 * 1024;
    public static final String VIDEO_DIR = resolveVideoDir();

    private static String resolveVideoDir() {
        File f = new File("videos");
        if (f.exists() && f.isDirectory()) {
            return f.getAbsolutePath();
        }
        File alt = new File("C:/Users/rusir/git/Streaming-App/Streaming_App/videos");
        if (alt.exists() && alt.isDirectory()) {
            return alt.getAbsolutePath();
        }
        return "videos";
    }
}
