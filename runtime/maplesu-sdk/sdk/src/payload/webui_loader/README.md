# webui_loader payload program

The current SDK compiles this source tree and publishes the resulting program in
its authenticated StorageLocator system directory. No embedded credential slot
or Key substitution exists in this generation.

## Launch contract

`webui_loader <module-id> <port> <caller-pid> <log-flag> --maplesu-webui-ready-v1 <ready-fd> <browser|manager> --maplesu-webui-directory-v1 <directory-fd>`

The SDK worker obtains current-task root before exec. The private directory pipe
carries only the existing 32-byte DeploymentDirectory path record; it grants no
authority. StartupReceipt reports the actual error/port and deployment directory.
Old launches without the directory are rejected instead of selecting a stored Key.

The loader resolves the current SDK's `_keyless_v1` WebUI entry symbol. Historical
ELF modules must be rebuilt; they are never called through a changed signature.
Missing module servers may use the documented static webroot server. The owner
process and module exit callback still govern the serving process lifetime.

CivetWeb 1.16 remains under its original MIT notices in `third_party/civetweb`.
Payload generation and formal SDK/helper/APK/device verification are separate stages.
