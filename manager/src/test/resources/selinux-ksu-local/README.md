# KernelSU-compatible local SELinux status regression

Run `manager/src/test/resources/selinux-ksu-local/run.ps1` from the repository root.
`-SourcePath` accepts a frozen production checker for a red/green comparison.

The complete production checker is compiled. The Shell fixture verifies an ordinary `sh`
program, the fixed `getenforce` command, resource closure, exact output interpretation, and
independence from SDK/Owner/Hook state. Existing owner-scope boundary files are reused only
as call traps; every scenario requires zero SDK/Owner/Hook calls. Real root authentication
and Android SELinux are outside this host fixture.

The fixed KernelSU baseline is `f4603d14bd7f3bf21206d6f7c622ddf1c049ade3`.
Its failed-command stderr suffix `Permission denied` maps to `Enforcing`; that is a
compatibility inference, distinct from a successful enforce read. Other failed or malformed
results remain `Unknown`. The ordinary getter never consumes the compatibility observation
cache. Those retained non-UI interfaces are checked separately for boot, invalidation and TTL.

The earlier owner-scope harness describes the superseded SDK-based implementation; its
historical results remain in `.tmp/issue-release-20261126/selinux-legacy-r3`.
