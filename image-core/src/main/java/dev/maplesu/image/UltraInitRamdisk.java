package dev.maplesu.image;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** The CPIO part of the existing ultra-init handoff; it never executes an input. */
final class UltraInitRamdisk {
    static final String ORIGINAL = "init.maplesu.orig";
    static final String DIRECTORY = ".maplesu_ultra";
    static final String BRIDGE = DIRECTORY + "/bridge.ko";
    static final String CREDENTIAL = DIRECTORY + "/credential";
    static final String DEPLOYMENT = DIRECTORY + "/deployment";

    private UltraInitRamdisk() {}

    static byte[] install(byte[] cpio, byte[] handoff, byte[] bridge) throws IOException {
        return install(cpio, handoff, bridge, null);
    }

    static byte[] install(byte[] cpio, byte[] handoff, byte[] bridge, String ksuInitSha256) throws IOException {
        return install(cpio, handoff, bridge, ksuInitSha256, null);
    }

    static byte[] install(byte[] cpio, byte[] handoff, byte[] bridge, String ksuInitSha256, byte[] credential) throws IOException {
        return install(cpio, handoff, bridge, ksuInitSha256, credential, null);
    }

    static byte[] install(byte[] cpio, byte[] handoff, byte[] bridge, String ksuInitSha256, byte[] credential,
                          byte[] deploymentNamespace) throws IOException {
        if (credential != null) UltraInitBootProducer.credentialShape(credential);
        if (deploymentNamespace != null) UltraInitBootProducer.deploymentNamespaceShape(deploymentNamespace);
        cpio = UltraInitForeignRamdisk.normalize(cpio, ksuInitSha256);
        RamdiskInspector.Report report = inspect(cpio);
        RamdiskInspector.Entry init = null;
        for (RamdiskInspector.Entry entry : report.entries) {
            String name = canonical(entry.path);
            if (owned(name)) throw error("PATH_CONFLICT:" + entry.path);
            if (!name.equals("init")) continue;
            if (init != null) throw error("DUPLICATE_INIT");
            init = entry;
        }
        if (init == null) throw error("INIT_MISSING");
        RamdiskInspector.Entry original = init;
        RamdiskInspector.Entry real = unique(report, "init.real"), ko = unique(report, "kernelsu.ko");
        RamdiskInspector.Entry derivative = unique(report, "sevenk.ko");
        if (ko != null && derivative != null) throw error("KSU_LAYOUT_CONFLICT:multiple-modules");
        if (ko == null) ko = derivative;
        if (init.sha256.equals(KernelIdentity.sha256(handoff))) throw error("HANDOFF_WITHOUT_RECOVERY");
        if (real != null || ko != null) {
            if (real == null || ko == null || !ko.isRegularFile() || ko.size == 0)
                throw error("KSU_LAYOUT_CONFLICT:init.real+kernelsu.ko");
            if (!init.isRegularFile() || init.size == 0 || init.uid != 0 || init.links != 1 || (init.mode & 0111) == 0)
                throw error("KSU_INIT_METADATA");
            if (ksuInitSha256 != null && !init.sha256.equals(ksuInitSha256)) throw error("KSU_INIT_PROVENANCE_MISMATCH");
            // KSU's KeepInitReal: execute the retained Android init, never the old ksuinit shim.
            original = real;
            if (real.sha256.equals(init.sha256) || real.sha256.equals(KernelIdentity.sha256(handoff)))
                throw error("KSU_ORIGINAL_INIT_CONFLICT");
        }
        if ((!original.isRegularFile() && !original.isSymbolicLink()) || original.size == 0 ||
                original.uid != 0 || original.links != 1 ||
                (original.isRegularFile() && (original.mode & 0111) == 0)) throw error("INIT_METADATA");
        if (original.isSymbolicLink()) {
            RamdiskInspector.Entry link = original;
            String parent = "";
            java.util.HashSet<String> visited = new java.util.HashSet<>();
            while (link != null && link.isSymbolicLink()) {
                BootImageRebuilder.cancelled();
                String target = new String(cpio, (int) link.offset, (int) link.size, StandardCharsets.ISO_8859_1);
                String name = linkTarget(target.startsWith("/") ? target : parent + target);
                if (target.indexOf('\0') >= 0 || name.isEmpty() || name.equals("init") || name.equals("init.real") ||
                        owned(name) || !visited.add(name)) throw error("INIT_SYMLINK_LOOP");
                link = unique(report, name);
                int slash = name.lastIndexOf('/'); parent = slash < 0 ? "" : name.substring(0, slash + 1);
            }
            if (link != null && (!link.isRegularFile() || (link.mode & 0111) == 0 || link.size == 0 ||
                    link.sha256.equals(init.sha256) || link.sha256.equals(KernelIdentity.sha256(handoff))))
                throw error("INIT_SYMLINK_TARGET");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        append(out, cpio, 0, init.headerOffset);
        byte[] renamed = rename(cpio, original, ORIGINAL);
        append(out, renamed, 0, renamed.length);
        byte[] shim = entry("init", 0100755, handoff, init.crc);
        append(out, shim, 0, shim.length);
        append(out, cpio, init.endOffset, report.trailerOffset - init.endOffset);
        for (byte[] added : new byte[][]{entry(DIRECTORY, 0040700, new byte[0], report.trailerCrc),
                entry(BRIDGE, 0100600, bridge, report.trailerCrc)}) append(out, added, 0, added.length);
        if (credential != null) {
            byte[] secret = entry(CREDENTIAL, 0100600, credential, report.trailerCrc);
            try { append(out, secret, 0, secret.length); }
            finally { Arrays.fill(secret, (byte) 0); }
        }
        if (deploymentNamespace != null) {
            byte[] record = entry(DEPLOYMENT, 0100600, deploymentNamespace, report.trailerCrc);
            append(out, record, 0, record.length);
        }
        append(out, cpio, report.trailerOffset, cpio.length - report.trailerOffset);
        byte[] result = out.toByteArray();
        RamdiskInspector.Report after = inspect(result);
        // All non-target headers, ordering, data, CRCs and padding must survive verbatim.
        int at = 0;
        for (RamdiskInspector.Entry before : report.entries) {
            if (before == init) { at += renamed.length + shim.length; continue; }
            int length = before.endOffset - before.headerOffset;
            if (!Arrays.equals(cpio, before.headerOffset, before.endOffset, result, at, at + length))
                throw error("NON_TARGET_CHANGED");
            at += length;
        }
        if (after.entries.size() != report.entries.size() + 3 + (credential == null ? 0 : 1) +
                (deploymentNamespace == null ? 0 : 1)) throw error("READBACK");
        if (credential != null) {
            RamdiskInspector.Entry secret = unique(after, CREDENTIAL);
            if (secret == null || secret.mode != 0100600 || secret.uid != 0 || secret.gid != 0 || secret.links != 1 ||
                    secret.size != 48 || !Arrays.equals(credential, 0, credential.length, result,
                            (int) secret.offset, (int) (secret.offset + secret.size))) throw error("CREDENTIAL_READBACK");
        }
        if (deploymentNamespace != null && !Arrays.equals(deploymentNamespace, readDeploymentNamespace(result)))
            throw error("DEPLOYMENT_NAMESPACE_READBACK");
        return result;
    }

    static byte[] readDeploymentNamespace(byte[] cpio) throws IOException {
        RamdiskInspector.Entry entry = unique(inspect(cpio), DEPLOYMENT);
        if (entry == null) return null;
        if (!entry.path.equals(DEPLOYMENT) || entry.mode != 0100600 || entry.uid != 0 || entry.gid != 0 ||
                entry.links != 1 || entry.size != 24) throw error("DEPLOYMENT_NAMESPACE_METADATA");
        byte[] record = Arrays.copyOfRange(cpio, (int) entry.offset, (int) (entry.offset + entry.size));
        UltraInitBootProducer.deploymentNamespaceShape(record);
        return record;
    }

    /** Called only after the caller verifies the complete owned-image manifest. */
    static byte[] readLegacyDeploymentNamespace(byte[] cpio) throws IOException {
        RamdiskInspector.Entry entry = unique(inspect(cpio), CREDENTIAL);
        if (entry == null) return null;
        if (!entry.path.equals(CREDENTIAL) || entry.mode != 0100600 || entry.uid != 0 || entry.gid != 0 ||
                entry.links != 1 || entry.size != 48) throw error("LEGACY_DEPLOYMENT_METADATA");
        byte[] credential = Arrays.copyOfRange(cpio, (int) entry.offset, (int) (entry.offset + entry.size));
        try {
            UltraInitBootProducer.credentialShape(credential);
            return java.nio.ByteBuffer.allocate(24).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .putInt(0x4e44534d).putShort((short) 1).putShort((short) 24)
                    .put(credential, 0, 16).array();
        } finally { Arrays.fill(credential, (byte) 0); }
    }

    private static RamdiskInspector.Entry unique(RamdiskInspector.Report report, String path) throws IOException {
        RamdiskInspector.Entry found = null;
        for (RamdiskInspector.Entry entry : report.entries) if (canonical(entry.path).equals(path)) {
            if (found != null) throw error("DUPLICATE_ENTRY:" + path);
            found = entry;
        }
        return found;
    }

    static boolean hasOwnedPath(byte[] cpio) throws IOException {
        for (RamdiskInspector.Entry entry : inspect(cpio).entries)
            if (owned(canonical(entry.path))) return true;
        return false;
    }

    private static boolean owned(String name) {
        return name.equals(ORIGINAL) || name.equals(DIRECTORY) || name.startsWith(DIRECTORY + "/") ||
                name.startsWith(ORIGINAL + "/");
    }

    private static String canonical(String path) {
        StringBuilder name = new StringBuilder();
        for (String part : path.split("/", -1)) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (name.length() > 0) name.append('/');
            name.append(part);
        }
        return name.toString();
    }

    private static String linkTarget(String path) {
        java.util.ArrayDeque<String> parts = new java.util.ArrayDeque<>();
        for (String part : path.split("/", -1)) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) { if (!parts.isEmpty()) parts.removeLast(); }
            else parts.addLast(part);
        }
        return String.join("/", parts);
    }

    static RamdiskInspector.Report inspect(byte[] cpio) throws IOException {
        RamdiskInspector.Report report = RamdiskInspector.inspectRamdisk(cpio);
        if (report.malformed || !"RAW".equals(report.compression))
            throw error("CPIO:" + report.errorCode);
        return report;
    }

    static byte[] rename(byte[] cpio, RamdiskInspector.Entry entry, String name) throws IOException {
        byte[] path = name.getBytes(StandardCharsets.US_ASCII);
        int dataAt = align(110 + path.length + 1);
        byte[] result = new byte[dataAt + entry.endOffset - (int) entry.offset];
        System.arraycopy(cpio, entry.headerOffset, result, 0, 110);
        hex8(result, 94, path.length + 1);
        System.arraycopy(path, 0, result, 110, path.length);
        System.arraycopy(cpio, (int) entry.offset, result, dataAt, entry.endOffset - (int) entry.offset);
        return result;
    }

    static byte[] entry(String name, int mode, byte[] content, boolean crc) throws IOException {
        byte[] path = name.getBytes(StandardCharsets.US_ASCII);
        int dataAt = align(110 + path.length + 1);
        if (content.length > RamdiskInspector.DEFAULT_MAX_BYTES - dataAt - 3) throw error("SIZE_LIMIT");
        byte[] result = new byte[dataAt + align(content.length)];
        System.arraycopy((crc ? "070702" : "070701").getBytes(StandardCharsets.US_ASCII), 0, result, 0, 6);
        long sum = 0; if (crc) for (byte value : content) sum += value & 255;
        long[] fields = {0, mode, 0, 0, 1, 0, content.length, 0, 0, 0, 0, path.length + 1, sum};
        for (int index = 0; index < fields.length; index++) hex8(result, 6 + index * 8, fields[index]);
        System.arraycopy(path, 0, result, 110, path.length);
        System.arraycopy(content, 0, result, dataAt, content.length);
        return result;
    }

    static void append(ByteArrayOutputStream out, byte[] bytes, int start, int length) throws IOException {
        if (length < 0 || length > RamdiskInspector.DEFAULT_MAX_BYTES - out.size()) throw error("SIZE_LIMIT");
        BootImageRebuilder.cancelled();
        out.write(bytes, start, length);
    }

    private static int align(int value) { return (value + 3) & ~3; }
    private static void hex8(byte[] bytes, int at, long value) {
        for (int index = 7; index >= 0; index--) {
            bytes[at + index] = (byte) "0123456789abcdef".charAt((int) (value & 15)); value >>>= 4;
        }
    }
    private static IOException error(String code) { return new IOException("ULTRA_INIT_CPIO_" + code); }
}
