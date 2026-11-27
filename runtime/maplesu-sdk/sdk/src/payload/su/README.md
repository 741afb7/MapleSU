# su payload program

Source tree for the `su` program the SDK installs as `<deployment dir>/u-00000000/su`.
`sdk/build.py` compiles this tree into `build/out/assets/su.bin`, and the
`maplesu_unsafe_su_install.o` member embeds those bytes as `maplesu::sdk::su_data`.

## Origin of the implementation

| Open-edition source | Used for |
| --- | --- |
| `.research/MapleSU/Lite_version/src/testRoot/kernel_root_kit/src/jni/su/su.cpp` | the whole CLI: option table, usage text, `-cn`/`-mm` legacy rewrites, `-c` command folding, login/keepenv/mount-master flags, environment and exec shape |
| `.research/MapleSU/Lite_version/src/testRoot/kernel_root_kit/src/jni/su/su.h` | request structure, default shell, version identity (`ROOT_VER_CODE` = 1, `ROOT_VERSION` = "1.0") |

The native execution gate validates the installed application, its full UID and
authorization, then applies its Profile and passes a one-shot session descriptor.
The program validates and consumes that receipt; no shared image credential is used.

## Contract

```
su [options] [-] [user [argument...]]
```

Options (identical to the reference usage text):

| Option | Behaviour |
| --- | --- |
| `-c, --command COMMAND` | pass `COMMAND` to the invoked shell; all remaining arguments are folded into one command string |
| `-h, --help` | print the help text on stdout, exit 0 |
| `-, -l, --login` | login shell: argv[0] is `-`, HOME/PWD come from the target user's home directory |
| `-m, -p, --preserve-environment` | keep the caller's environment |
| `-s, --shell SHELL` | use `SHELL` instead of `/system/bin/sh` |
| `-v, --version` | print `1.0`, exit 0 |
| `-V` | print the version code `1`, exit 0 |
| `-mm, -M, --mount-master` | join the global (init) mount namespace before exec |
| `-z, --context CTX` | legacy flag, accepted and ignored (kept for old callers) |

Exit statuses: `0` after help/version and after a successful exec attempt; `2`
for a usage error (getopt diagnostic, then the help text on stderr); the shell's
own status otherwise. A failed session scan or invalid receipt exits 1 before a
shell starts. With no receipt only an already-root caller may continue.

Privileged operations the installer expects:

1. **Profile receipt** - validate the inherited kernel session descriptor, consume
   it exactly once and verify all real/effective/saved UID/GID values. A failed
   receipt is terminal; no further grant is attempted.
2. **Target identity** - a user name resolves through `getpwnam`, an all-digit
   argument is the uid; for a non-root target the program restores effective uid
   0 and applies `setresgid`/`setresuid` so saved ids are replaced too.
3. **Mount namespace** - `--mount-master` opens `/proc/1/ns/mnt` and calls
   `setns(CLONE_NEWNS)`.
4. **Exec** - the requested shell is executed with the original argv/environment
   conventions; no shell interpolation happens in the program itself.

## Build

`sdk/build.py --payloads` runs `sdk/tools/build_payloads.py --only su`; the recipe
lives in `program.json` (AArch64 Android PIE, static libc++, `-fno-exceptions
-fno-rtti`, stripped).  The installed bytes are published as built. The program has no feature token or
install-time secret rewrite.
