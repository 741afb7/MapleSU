package dev.maplesu.acceptance.gateway;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.widget.TextView;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import org.json.JSONArray;
import org.json.JSONObject;

/** Independent ordinary test identity; active requests require operation=gateway. */
public final class GatewayActivity extends Activity {
    static { System.loadLibrary("gatewayprobe"); }
    private static native String nativeGateway(int variant, long requestId);
    private static final String[] VARIANTS = {"query", "echo", "wrong-prefix", "wrong-selector", "bad-magic"};

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView text = new TextView(this);
        setContentView(text);
        new Thread(() -> {
            JSONObject result = new JSONObject();
            File directory = null;
            try {
                result.put("operation", getIntent().getStringExtra("operation"))
                    .put("case", getIntent().getStringExtra("case"))
                    .put("package", getPackageName()).put("fullUid", Process.myUid())
                    .put("pid", Process.myPid()).put("process64Bit", Process.is64Bit())
                    .put("supportedAbis", new JSONArray(Build.SUPPORTED_ABIS));
                if (!"gateway".equals(getIntent().getStringExtra("operation")))
                    throw new IllegalArgumentException("explicit operation=gateway required");
                int count = getIntent().getIntExtra("samples", 31);
                if (count < 1 || count > 100) throw new IllegalArgumentException("samples must be 1..100");
                String selected = getIntent().getStringExtra("variant");
                if (selected == null) selected = "all";
                int only = -1;
                if (!"all".equals(selected)) {
                    for (int index = 0; index < VARIANTS.length; ++index)
                        if (VARIANTS[index].equals(selected)) only = index;
                    if (only < 0) throw new IllegalArgumentException("unknown gateway variant");
                }
                int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
                PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), flags);
                Signature[] signatures = Build.VERSION.SDK_INT >= 28 ? info.signingInfo.getApkContentsSigners() : info.signatures;
                JSONArray signers = new JSONArray();
                for (Signature signature : signatures) signers.put(hex(MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())));
                result.put("signerSha256", signers).put("variant", selected).put("requestedSamplesPerVariant", count)
                    .put("selfStatusBefore", readSelf("status")).put("selfSelinuxBefore", readSelf("attr/current"));
                directory = new File(getExternalFilesDir(null), "gateway-" + System.currentTimeMillis() + "-" + java.util.UUID.randomUUID());
                Files.createDirectory(directory.toPath());
                result.put("observationDirectory", directory.getAbsolutePath());
                JSONArray samples = new JSONArray();
                result.put("samples", samples);
                for (int round = 0; round < count; ++round) {
                    for (int kind = 0; kind < VARIANTS.length; ++kind) {
                        if (only >= 0 && only != kind) continue;
                        JSONObject row = new JSONObject(nativeGateway(kind, (long)round * VARIANTS.length + kind + 1))
                            .put("round", round).put("caseOrder", kind).put("sampleIndex", samples.length());
                        String name = String.format(java.util.Locale.ROOT, "sample-%04d.json", samples.length());
                        Files.write(new File(directory, name).toPath(), row.toString().getBytes(StandardCharsets.UTF_8));
                        row.put("rawFile", name);
                        samples.put(row);
                    }
                }
                result.put("completedSamples", samples.length()).put("selfStatusAfter", readSelf("status"))
                    .put("selfSelinuxAfter", readSelf("attr/current"));
            } catch (Exception error) {
                try { result.put("probeError", error.getClass().getName() + ":" + error.getMessage()); }
                catch (org.json.JSONException ignored) { }
            }
            String json = result.toString();
            try {
                byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                if (directory != null) Files.write(new File(directory, "result.json").toPath(), bytes);
                Files.write(new File(getExternalFilesDir(null), "last-result.json").toPath(), bytes);
            } catch (Exception error) { android.util.Log.e("MapleGatewayProbe", "RESULT_WRITE_FAILED", error); }
            runOnUiThread(() -> text.setText(json));
        }, "explicit-gateway-probe").start();
    }

    private static JSONObject readSelf(String name) throws Exception {
        JSONObject result = new JSONObject();
        long started = System.nanoTime();
        try {
            result.put("base64", android.util.Base64.encodeToString(
                Files.readAllBytes(new File("/proc/self/" + name).toPath()), android.util.Base64.NO_WRAP));
        } catch (Exception error) {
            result.put("readError", error.getClass().getName() + ":" + error.getMessage());
            if (error instanceof android.system.ErrnoException)
                result.put("errno", ((android.system.ErrnoException)error).errno);
        }
        return result.put("readNanos", System.nanoTime() - started);
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte value : bytes) out.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return out.toString();
    }
}
