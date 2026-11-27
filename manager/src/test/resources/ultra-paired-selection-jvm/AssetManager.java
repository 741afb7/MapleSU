package android.content.res;
import java.io.*;
import java.util.*;
public class AssetManager {
    private final Map<String, byte[]> files;
    public final List<String> reads = new ArrayList<>();
    public AssetManager(Map<String, byte[]> files) { this.files = files; }
    public InputStream open(String path) throws IOException {
        reads.add(path);
        if (!files.containsKey(path)) throw new FileNotFoundException(path);
        return new ByteArrayInputStream(files.get(path));
    }
    public String[] list(String path) { return new String[0]; }
}
