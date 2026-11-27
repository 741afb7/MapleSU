package dev.maplesu.acceptance.su;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;
import android.widget.TextView;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.json.JSONArray;

/** An ordinary application process; SU is requested only by an explicit activity launch. */
public final class ProbeActivity extends Activity {
    static { System.loadLibrary("suprobe"); }
    private static native String nativeIdentity();
    private static native String[] nativeProperty(String name);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView text = new TextView(this);
        setContentView(text);
        new Thread(() -> {
            JSONObject result = new JSONObject();
            java.lang.Process child = null;
            try {
                String expectation = getIntent().getStringExtra("expect");
                String operation = getIntent().getStringExtra("operation");
                if (operation == null) operation = "observe";
                result.put("operation", operation);
                result.put("case", getIntent().getStringExtra("case"));
                result.put("package", getPackageName()).put("fullUid", Process.myUid());
                result.put("process64Bit", Process.is64Bit());
                result.put("before", new JSONObject(nativeIdentity()));
                int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
                PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), flags);
                Signature[] signatures = Build.VERSION.SDK_INT >= 28 ? info.signingInfo.getApkContentsSigners() : info.signatures;
                StringBuilder certificate = new StringBuilder();
                for (byte value : MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray()))
                    certificate.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
                result.put("signerSha256", certificate.toString());
                if ("observe".equals(operation)) {
                    observeOwnProcess(result);
                } else if ("su".equals(operation)) {
                child = new ProcessBuilder("/system/bin/su", "-c",
                    "id; cat /proc/$$/status; readlink /proc/$$/exe").redirectErrorStream(true).start();
                child.getOutputStream().close();
                StringBuffer output = new StringBuffer();
                final java.lang.Process running = child;
                Thread reader = new Thread(() -> {
                    try (java.io.InputStream stream = running.getInputStream()) {
                        byte[] buffer = new byte[1024];
                        for (int count; (count = stream.read(buffer)) >= 0; )
                            if (output.length() < 16384) output.append(new String(buffer, 0, count, StandardCharsets.UTF_8));
                    } catch (java.io.IOException error) { output.append("\nREAD_ERROR:").append(error.getClass().getSimpleName()); }
                });
                reader.start();
                boolean finished = child.waitFor(15, TimeUnit.SECONDS);
                if (!finished) { child.destroyForcibly(); child.waitFor(2, TimeUnit.SECONDS); }
                reader.join(2000);
                int code = finished ? child.exitValue() : -1;
                boolean root = code == 0 && output.toString().matches("(?s)^uid=0\\(.*");
                result.put("exitCode", code).put("timedOut", !finished).put("outputComplete", !reader.isAlive());
                result.put("suObservedRoot", root).put("output", output.toString());
                JSONObject before = result.getJSONObject("before");
                JSONObject after = new JSONObject(nativeIdentity());
                boolean identity = Process.myUid() > 0 && before.toString().equals(after.toString()) &&
                    before.getInt("uidError") == 0 && before.getInt("gidError") == 0 &&
                    before.getInt("pointerBits") == (Process.is64Bit() ? 64 : 32);
                for (int index = 0; index < 3; index++)
                    identity &= before.getJSONArray("uids").getInt(index) == Process.myUid();
                result.put("after", after).put("callerIdentityVerified", identity);
                if ("allow".equals(expectation) || "deny".equals(expectation))
                    result.put("expectationMet", identity && finished && !reader.isAlive() &&
                        ("allow".equals(expectation) ? root : code != 0 && !root));
                } else throw new IllegalArgumentException("unknown operation");
            } catch (Exception error) {
                try { result.put("probeError", error.getClass().getName() + ":" + error.getMessage()); }
                catch (org.json.JSONException ignored) { }
            } finally {
                if (child != null && child.isAlive()) child.destroyForcibly();
            }
            String json = result.toString();
            try {
                byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                if (result.has("observationDirectory"))
                    Files.write(new File(result.getString("observationDirectory"), "result.json").toPath(), bytes);
                Files.write(new File(getExternalFilesDir(null), "last-result.json").toPath(), bytes);
            }
            catch (Exception error) { Log.e("MapleSuProbe", "RESULT_WRITE_FAILED", error); }
            Log.i("MapleSuProbe", json);
            runOnUiThread(() -> text.setText(json));
        }, "ordinary-su-request").start();
    }

    /** Explicit, bounded ordinary-application reads; no SU, SDK or subprocess. */
    private void observeOwnProcess(JSONObject result) throws Exception {
        int count = getIntent().getIntExtra("samples", 1);
        int interval = getIntent().getIntExtra("intervalMs", 0);
        if (count < 1 || count > 50 || interval < 0 || interval > 1000)
            throw new IllegalArgumentException("samples must be 1..50; intervalMs must be 0..1000");
        File directory = new File(getExternalFilesDir(null), "observe-" + System.currentTimeMillis() +
            "-" + java.util.UUID.randomUUID());
        Files.createDirectory(directory.toPath());
        result.put("observationDirectory", directory.getAbsolutePath()).put("requestedSamples", count)
            .put("intervalMs", interval);
        JSONArray samples = new JSONArray();
        result.put("samples", samples);
        for (int index = 0; index < count; index++) {
            if (index != 0 && interval != 0) Thread.sleep(interval);
            File sample = new File(directory, String.format(java.util.Locale.ROOT, "sample-%03d", index));
            Files.createDirectory(sample.toPath());
            JSONObject observations = new JSONObject().put("index", index)
                .put("wallTimeMillis", System.currentTimeMillis())
                .put("startedNanos", System.nanoTime()).put("before", new JSONObject(nativeIdentity()));
            for (String name : new String[] {"mountinfo", "attr/current", "status", "maps", "fd"}) {
                JSONObject row = new JSONObject();
                long started = System.nanoTime();
                try {
                    byte[] bytes;
                    if ("fd".equals(name)) {
                        java.util.ArrayList<String> descriptors = directoryNames(new File("/proc/self/fd"));
                        java.util.Collections.sort(descriptors);
                        StringBuilder links = new StringBuilder();
                        for (String descriptor : descriptors) {
                            links.append(descriptor).append(' ');
                            try { links.append(android.system.Os.readlink("/proc/self/fd/" + descriptor)); }
                            catch (android.system.ErrnoException error) { links.append("READ_ERROR:").append(error.errno); }
                            links.append('\n');
                        }
                        bytes = links.toString().getBytes(StandardCharsets.UTF_8);
                    } else bytes = Files.readAllBytes(new File("/proc/self/" + name).toPath());
                    row.put("readNanos", System.nanoTime() - started);
                    saveRaw(sample, "self-" + name.replace('/', '-') + ".txt", bytes, row);
                } catch (Exception error) { failed(row, error, started); }
                observations.put(name, row);
            }
            observations.put("properties", observeProperties(sample));
            observations.put("paths", observePaths(sample));
            observations.put("processes", observeProcesses(sample));
            observations.put("after", new JSONObject(nativeIdentity()));
            Files.write(new File(sample, "observations.json").toPath(), observations.toString().getBytes(StandardCharsets.UTF_8));
            samples.put(observations);
        }
        if (samples.length() != 0) result.put("observations", samples.getJSONObject(0));
        result.put("completedSamples", samples.length()).put("after", new JSONObject(nativeIdentity()));
    }

    private static java.util.ArrayList<String> directoryNames(File directory) throws Exception {
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        try (java.nio.file.DirectoryStream<java.nio.file.Path> entries = Files.newDirectoryStream(directory.toPath())) {
            for (java.nio.file.Path entry : entries) names.add(entry.getFileName().toString());
        }
        return names;
    }

    private JSONObject observeProperties(File sample) throws Exception {
        JSONObject properties = new JSONObject();
        for (String name : new String[] {"ro.boot.verifiedbootstate", "ro.boot.flash.locked",
                "ro.boot.vbmeta.device_state", "ro.boot.selinux", "ro.build.selinux", "ro.debuggable",
                "ro.secure", "ro.adb.secure", "ro.kernel.qemu", "ro.product.cpu.abilist",
                "ro.product.cpu.abilist32", "ro.dalvik.vm.native.bridge", "persist.sys.usb.config"}) {
            JSONObject row = new JSONObject();
            long started = System.nanoTime();
            try {
                String[] value = nativeProperty(name);
                row.put("readNanos", System.nanoTime() - started).put("length", Integer.parseInt(value[0]))
                    .put("errnoObserved", Integer.parseInt(value[1])).put("value", value[2]);
                if (Integer.parseInt(value[0]) == 0)
                    row.put("readState", "EMPTY_MISSING_OR_NOT_READABLE");
                else row.put("readState", "READ");
            } catch (Exception error) { failed(row, error, started); }
            properties.put(name, row);
        }
        JSONObject row = new JSONObject();
        saveRaw(sample, "properties.json", properties.toString().getBytes(StandardCharsets.UTF_8), row);
        return row;
    }

    private JSONObject observePaths(File sample) throws Exception {
        JSONObject paths = new JSONObject();
        for (String path : new String[] {"/system/bin/su", "/system/xbin/su", "/sbin/su", "/su",
                "/data/adb", "/data/adb/maplesu", "/data/adb/maplesu/ultra-identity", "/data/adb/modules",
                "/data/adb/ksu", "/dev/kernelsu", "/sys/fs/selinux/enforce", "/sys/fs/selinux/policy",
                "/proc/sys/kernel/kptr_restrict", "/proc/1/mountinfo"}) {
            JSONObject entry = new JSONObject();
            long started = System.nanoTime();
            try {
                android.system.StructStat stat = android.system.Os.lstat(path);
                entry.put("lstatNanos", System.nanoTime() - started).put("mode", stat.st_mode)
                    .put("uid", stat.st_uid).put("gid", stat.st_gid).put("size", stat.st_size)
                    .put("inode", stat.st_ino).put("device", stat.st_dev);
            } catch (Exception error) { failed(entry, error, started); }
            JSONObject access = new JSONObject();
            int[] modes = {android.system.OsConstants.F_OK, android.system.OsConstants.R_OK,
                android.system.OsConstants.W_OK, android.system.OsConstants.X_OK};
            String[] labels = {"exists", "read", "write", "execute"};
            for (int index = 0; index < modes.length; index++) {
                JSONObject check = new JSONObject();
                started = System.nanoTime();
                try { check.put("allowed", android.system.Os.access(path, modes[index]))
                    .put("readNanos", System.nanoTime() - started); }
                catch (Exception error) { failed(check, error, started); }
                access.put(labels[index], check);
            }
            entry.put("access", access);
            paths.put(path, entry);
        }
        JSONObject row = new JSONObject();
        saveRaw(sample, "paths.json", paths.toString().getBytes(StandardCharsets.UTF_8), row);
        return row;
    }

    private JSONObject observeProcesses(File sample) throws Exception {
        JSONObject processes = new JSONObject();
        long started = System.nanoTime();
        java.util.ArrayList<String> names;
        try {
            names = directoryNames(new File("/proc"));
            java.util.Collections.sort(names);
            processes.put("listNanos", System.nanoTime() - started).put("directoryEntries", new JSONArray(names));
        } catch (Exception error) {
            failed(processes, error, started);
            names = new java.util.ArrayList<>();
        }
        JSONObject entries = new JSONObject();
        for (String name : names) {
            if (!name.matches("[0-9]+")) continue;
            JSONObject process = new JSONObject();
            for (String leaf : new String[] {"cmdline", "status", "attr/current", "exe"}) {
                JSONObject row = new JSONObject();
                started = System.nanoTime();
                try {
                    String path = "/proc/" + name + "/" + leaf;
                    if ("exe".equals(leaf)) row.put("link", android.system.Os.readlink(path));
                    else row.put("base64", android.util.Base64.encodeToString(
                        Files.readAllBytes(new File(path).toPath()), android.util.Base64.NO_WRAP));
                    row.put("readNanos", System.nanoTime() - started);
                } catch (Exception error) { failed(row, error, started); }
                process.put(leaf, row);
            }
            entries.put(name, process);
        }
        processes.put("processes", entries);
        JSONObject row = new JSONObject().put("listedProcesses", entries.length());
        saveRaw(sample, "processes.json", processes.toString().getBytes(StandardCharsets.UTF_8), row);
        return row;
    }

    private static void failed(JSONObject row, Exception error, long started) throws Exception {
        row.put("readNanos", System.nanoTime() - started)
            .put("readError", error.getClass().getName() + ":" + error.getMessage());
        if (error instanceof android.system.ErrnoException)
            row.put("errno", ((android.system.ErrnoException) error).errno);
        Throwable cause = error.getCause();
        if (cause instanceof android.system.ErrnoException)
            row.put("errno", ((android.system.ErrnoException) cause).errno);
    }

    private static void saveRaw(File sample, String name, byte[] bytes, JSONObject row) throws Exception {
        StringBuilder digest = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes))
            digest.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        Files.write(new File(sample, name).toPath(), bytes);
        row.put("bytes", bytes.length).put("sha256", digest.toString()).put("rawFile", name);
    }
}
