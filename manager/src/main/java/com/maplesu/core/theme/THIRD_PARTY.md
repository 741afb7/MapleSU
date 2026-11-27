FPT container, resource selection and classic palette adapted from LyraVoid/FinaSU,
commit a839ce2d742f0f1450a3aad20bdc14bc060da840, GPL-3.0.
Original copyright and project license apply. The format secret is a public file-format
constant, independent of service credentials. MapleSU does not use FinaSU JNI tokens.
SukiSU / Nova catalog, device access, SKTH AES-GCM container and semantic appearance
mapping follow SukiSU-Nova/SukiSU-Nova, commit
1439545f12bffe0263690ba862139b70ee5f20cf, GPL-3.0.
Upstream ThemePackageCrypto.kt / ThemePackageStorage.kt / ThemeStoreDefaults.kt /
ThemeStoreAccessRepository.kt / AppearanceOverlay.kt / AppearanceOverlayColors.kt
and BottomBarIcons.kt define the protocol. The historical SukiSU-Ultra repository
redirects to that same project. The AES key is a public theme file-format constant;
per-device access and refresh credentials are obtained from the official service,
kept in private app preferences, and excluded from theme documents and exports.
Nova-only home layout/sticker transforms and image effects are retained verbatim,
with import warnings; MapleSU's own UI skin and home arrangement remain independent.
