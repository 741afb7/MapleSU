# SELinux system status focused regression

Run from the repository root:

```powershell
& manager/src/test/resources/selinux-owner-scope/run.ps1
```

The runner compiles the complete production `SELinuxChecker.kt` and `UltraLegacyCredential.kt`,
this fixture's boundary files, and the existing six `SELinuxCheckerTest` cases. It uses the cached Kotlin 2.4.20 compiler and
the current Java executable without Gradle or an APK build. `-SourcePath` accepts a frozen before
version; `-OwnerOnly` runs the first Legacy fallback regression for before/after comparison.

The fixture controls Android clock, mode activation, protected preferences, Hook availability,
and SDK replies. It does not reproduce the native identity producer or Android SELinux. The
local-source cases read real temporary files through the internal IO-only overload; denied-file
cases reach the real checker's SDK fallback. The tests preserve the original error/Unknown
result, allow independent system facts without an Owner, and use the real Legacy verifier for
authenticated root fallback. Barriers cover concurrent reads, boot/activation/revision changes,
late replies and credential replacement during root commands. Every scenario asserts that no
Owner query or additional root transport was started.
