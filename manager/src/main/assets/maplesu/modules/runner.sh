#!/system/bin/sh
# Invoked only by the authenticated Lite executor, with literal argv paths.
set -e
MODDIR="$1"
MODPATH="$MODDIR"
SCRIPT="$2"
ZIPFILE="$3"
LEGACY_INSTALLER="${4:-}"
TMPDIR="$MODDIR/.maplesu-tmp"
export MODDIR MODPATH ZIPFILE TMPDIR
export NVBASE=/data/adb MODULEROOT="${MODPATH%/*}" OUTFD=1
export ASH_STANDALONE=1

# `magisk` is a compatibility shim (LSPosed calls the command directly), but KSU-native
# installers abort when `which magisk` succeeds - measured: Zygisk Next prints "Multiple
# root implementation is NOT supported!". KSU ships no magisk binary, so the shim lives in
# BINARY_DIR/compat and only installers that actually invoke it get that directory on PATH.
if [ -d /data/adb/maplesu/bin/compat ]; then
  for _f in "$MODDIR"/*.sh; do
    # Include sourced helpers (LSPosed's util_functions.sh), but not probes such
    # as `which magisk`: exposing the shim there reports a second Root manager.
    if [ -f "$_f" ] && /system/bin/sed '/^[[:space:]]*#/d' "$_f" | /system/bin/grep -qE '(^|[^[:alnum:]_/])magisk[[:space:]]+(-[^[:space:];|&)]+|version)([[:space:];|&)]|$)'; then
      PATH="/data/adb/maplesu/bin/compat:$PATH"
      export PATH
      break
    fi
  done
fi
export MAPLESU=true MAPLESU_BACKEND=lite_script BOOTMODE=true
export KSU=true KSU_HAS_HOT_INSTALL=true
maplesu_require_runtime_facts() {
  case "${KSU_VER_CODE:-}" in ''|*[!0-9]*) return 75 ;; esac
  case "${KSU_KERNEL_VER_CODE:-}" in ''|*[!0-9]*) return 75 ;; esac
  case "${KSU_UAPI_VER:-}" in ''|*[!0-9]*) return 75 ;; esac
  [ -n "${KSU_VER:-}" ] || return 75
  case "${KSU_FACT_SOURCE:-}" in
    legacy)
      # This Root transport has no installation-mode fact. Preserve that unknown.
      [ "${MAPLESU_RUNTIME_MODE:-}" = unknown ] || return 75
      [ -z "${KSU_RUNTIME_MODE+x}" ] && [ -z "${KSU_LATE_LOAD+x}" ] || return 75
      [ "${KSU_KERNEL_VER_CODE}" = 0 ] && [ "${KSU_UAPI_VER}" = 0 ] || return 75
      [ "${MAPLESU_KSU_COMPAT:-}" = 1 ] || return 75
      return 0 ;;
    hook)
      [ "${MAPLESU_RUNTIME_MODE:-}" = hotload ] && [ "${KSU_RUNTIME_MODE:-}" = late-load ] || return 75
      [ "${KSU_KERNEL_VER_CODE}" = 0 ] && [ "${KSU_UAPI_VER}" = 0 ] || return 75
      [ "${MAPLESU_KSU_COMPAT:-}" = 1 ] || return 75 ;;
    '') ;;
    *) return 75 ;;
  esac
  case "${KSU_RUNTIME_MODE:-}" in built-in|lkm|late-load) ;; *) return 75 ;; esac
  if [ "${KSU_RUNTIME_MODE}" = late-load ]; then
    [ "${KSU_LATE_LOAD:-}" = 1 ] || return 75
  else
    [ -z "${KSU_LATE_LOAD+x}" ] || [ "${KSU_LATE_LOAD}" = 0 ] || return 75
    unset KSU_LATE_LOAD
  fi
  return 0
}
if ! maplesu_require_runtime_facts; then
  printf '%s\n' 'maplesu: authenticated KSU runtime facts missing or invalid' >&2
  exit 75
fi

# KSU appends BINARY_DIR to PATH for every script it runs (module.rs:63-88), so a
# customize.sh can call `sepolicy` / `resetprop` / `ksud` by bare name. Append, never
# prepend: platform binaries keep priority. Without this the helpers silently report
# "sepolicy checker is unavailable" even though the tool was published.
export PATH="${PATH:-/system/bin:/system/xbin}:/data/adb/maplesu/bin"

# Module policy and filesystem projections are dispatched by separate boot tasks.
# This runner only executes the selected script; publishing tools alone creates
# neither mounts nor base policy. The mount task honors skip_mount and delegates
# to an installed metamodule, or uses overlayfs/tmpfs plus bind mounts on demand.
# These managers are detected by nonempty variables in several module installers.
# Manager identity. KSU-native module installers do not merely read these for display:
# they select the KSU code path with them and abort otherwise (Zygisk Next prints
# "Please install from KernelSU or Magisk app", LSPosed prints "Please install Magisk
# v27+"). MapleSU implements the KSU module contract, so it declares the KSU identity
# that contract defines. This is a compatibility declaration, not a claim that a
# KernelSU kernel driver is loaded. Like KSU installer.sh, preserve the legacy
# Magisk version variables for shared installers, while leaving MAGISK unset.
export KSU_MODULE="${MODDIR##*/}"
unset MAGISK
export MAGISK_VER=25.2 MAGISK_VER_CODE=25200
export POSTFSDATAD="$NVBASE/post-fs-data.d" SERVICED="$NVBASE/service.d"
mkdir -p "$TMPDIR"
ui_print() { printf '%s\n' "$*"; }
abort() { printf '! %s\n' "$*" >&2; exit 1; }
# Platform unzip can reject XZ (method 95); use the bundled KSU-compatible applet.
unzip() { /data/adb/maplesu/bin/busybox unzip "$@"; }
set_perm() {
  # 4 args = target uid gid mode; 5 args adds an SELinux context. Applying an
  # *existing* type with chcon changes no policy and loads no rule, so it is allowed
  # here; declaring a new type still belongs to the policy loader.
  [ "$#" -eq 4 ] || [ "$#" -eq 5 ] || abort 'set_perm requires target uid gid mode [context]'
  chown "$2:$3" "$1"
  chmod "$4" "$1"
  chcon "${5:-u:object_r:system_file:s0}" "$1"
}
set_perm_recursive() {
  # 5 args = target uid gid directory-mode file-mode; 6 args adds a context.
  [ "$#" -eq 5 ] || [ "$#" -eq 6 ] || abort 'set_perm_recursive requires target uid gid directory-mode file-mode [context]'
  chown -R "$2:$3" "$1"
  find "$1" -type d -exec chmod "$4" '{}' ';'
  find "$1" -type f -exec chmod "$5" '{}' ';'
  find "$1" -exec chcon "${6:-u:object_r:system_file:s0}" '{}' ';'
}
# Installer helpers the pinned packages actually call. Measured offline from the
# three fixed ZIPs: grep_prop and check_sepolicy are both expected from the installer
# (Zygisk Next: grep_prop x1, check_sepolicy x1; LSPosed: grep_prop x1; TEESimulator:
# grep_prop x1), and neither used to exist here.
# Installer helper semantics follow the fixed KernelSU userspace.
toupper() {
  echo "$@" | tr '[:lower:]' '[:upper:]'
}

grep_cmdline() {
  local REGEX="s/^$1=//p"
  { echo $(cat /proc/cmdline)$(sed -e 's/[^"]//g' -e 's/""//g' /proc/cmdline) | xargs -n 1; \
    sed -e 's/ = /=/g' -e 's/, /,/g' -e 's/"//g' /proc/bootconfig; \
  } 2>/dev/null | sed -n "$REGEX"
}

grep_prop() {
  local REGEX="s/$1=//p"
  shift
  local FILES=$@
  [ -z "$FILES" ] && FILES='/system/build.prop'
  cat $FILES 2>/dev/null | dos2unix | sed -n "$REGEX" | head -n 1 | xargs
}

grep_get_prop() {
  local result=$(grep_prop $@)
  if [ -z "$result" ]; then
    # Fallback to getprop
    getprop "$1"
  else
    echo $result
  fi
}

is_mounted() {
  grep -q " $(readlink -f $1) " /proc/mounts 2>/dev/null
  return $?
}

print_title() {
  local len line1len line2len bar
  line1len=$(echo -n $1 | wc -c)
  line2len=$(echo -n $2 | wc -c)
  len=$line2len
  [ $line1len -gt $line2len ] && len=$line1len
  len=$((len + 2))
  bar=$(printf "%${len}s" | tr ' ' '*')
  ui_print "$bar"
  ui_print " $1 "
  [ "$2" ] && ui_print " $2 "
  ui_print "$bar"
}

setup_flashable() {
  ensure_bb
  $BOOTMODE && return
  if [ -z $OUTFD ] || readlink /proc/$$/fd/$OUTFD | grep -q /tmp; then
    # We will have to manually find out OUTFD
    for FD in `ls /proc/$$/fd`; do
      if readlink /proc/$$/fd/$FD | grep -q pipe; then
        if ps | grep -v grep | grep -qE " 3 $FD |status_fd=$FD"; then
          OUTFD=$FD
          break
        fi
      fi
    done
  fi
  recovery_actions
}

ensure_bb() {
  :
}

recovery_actions() {
  :
}

recovery_cleanup() {
  :
}

find_block() {
  local BLOCK DEV DEVICE DEVNAME PARTNAME UEVENT
  for BLOCK in "$@"; do
    DEVICE=`find /dev/block \( -type b -o -type c -o -type l \) -iname $BLOCK | head -n 1` 2>/dev/null
    if [ ! -z $DEVICE ]; then
      readlink -f $DEVICE
      return 0
    fi
  done
  # Fallback by parsing sysfs uevents
  for UEVENT in /sys/dev/block/*/uevent; do
    DEVNAME=`grep_prop DEVNAME $UEVENT`
    PARTNAME=`grep_prop PARTNAME $UEVENT`
    for BLOCK in "$@"; do
      if [ "$(toupper $BLOCK)" = "$(toupper $PARTNAME)" ]; then
        echo /dev/block/$DEVNAME
        return 0
      fi
    done
  done
  # Look just in /dev in case we're dealing with MTD/NAND without /dev/block devices/links
  for DEV in "$@"; do
    DEVICE=`find /dev \( -type b -o -type c -o -type l \) -maxdepth 1 -iname $DEV | head -n 1` 2>/dev/null
    if [ ! -z $DEVICE ]; then
      readlink -f $DEVICE
      return 0
    fi
  done
  return 1
}

setup_mntpoint() {
  local POINT=$1
  [ -L $POINT ] && mv -f $POINT ${POINT}_link
  if [ ! -d $POINT ]; then
    rm -f $POINT
    mkdir -p $POINT
  fi
}

mount_name() {
  local PART=$1
  local POINT=$2
  local FLAG=$3
  setup_mntpoint $POINT
  is_mounted $POINT && return
  # First try mounting with fstab
  mount $FLAG $POINT 2>/dev/null
  if ! is_mounted $POINT; then
    local BLOCK=$(find_block $PART)
    mount $FLAG $BLOCK $POINT || return
  fi
  ui_print "- Mounting $POINT"
}

mount_ro_ensure() {
  # We handle ro partitions only in recovery
  $BOOTMODE && return
  local PART=$1
  local POINT=$2
  mount_name "$PART" $POINT '-o ro'
  is_mounted $POINT || abort "! Cannot mount $POINT"
}

mount_partitions() {
  # Check A/B slot
  SLOT=`grep_cmdline androidboot.slot_suffix`
  if [ -z $SLOT ]; then
    SLOT=`grep_cmdline androidboot.slot`
    [ -z $SLOT ] || SLOT=_${SLOT}
  fi
  [ -z $SLOT ] || ui_print "- Current boot slot: $SLOT"

  # Mount ro partitions
  if is_mounted /system_root; then
    umount /system 2&>/dev/null
    umount /system_root 2&>/dev/null
  fi
  mount_ro_ensure "system$SLOT app$SLOT" /system
  if [ -f /system/init -o -L /system/init ]; then
    SYSTEM_ROOT=true
    setup_mntpoint /system_root
    if ! mount --move /system /system_root; then
      umount /system
      umount -l /system 2>/dev/null
      mount_ro_ensure "system$SLOT app$SLOT" /system_root
    fi
    mount -o bind /system_root/system /system
  else
    SYSTEM_ROOT=false
    grep ' / ' /proc/mounts | grep -qv 'rootfs' || grep -q ' /system_root ' /proc/mounts && SYSTEM_ROOT=true
  fi
  # /vendor is used only on some older devices for recovery AVBv1 signing so is not critical if fails
  [ -L /system/vendor ] && mount_name vendor$SLOT /vendor '-o ro'
  $SYSTEM_ROOT && ui_print "- Device is system-as-root"

  # Mount sepolicy rules dir locations in recovery (best effort)
  if ! $BOOTMODE; then
    mount_name "cache cac" /cache
    mount_name metadata /metadata
    mount_name persist /persist
  fi
}

api_level_arch_detect() {
  API=$(grep_get_prop ro.build.version.sdk)
  ABI=$(grep_get_prop ro.product.cpu.abi)
  if [ "$ABI" = "x86" ]; then
    ARCH=x86
    ABI32=x86
    IS64BIT=false
  elif [ "$ABI" = "arm64-v8a" ]; then
    ARCH=arm64
    ABI32=armeabi-v7a
    IS64BIT=true
  elif [ "$ABI" = "x86_64" ]; then
    ARCH=x64
    ABI32=x86
    IS64BIT=true
  elif [ "$ABI" = "riscv64" ]; then
    ARCH=riscv64
    ABI32=
    IS64BIT=true
  else
    ARCH=arm
    ABI=armeabi-v7a
    ABI32=armeabi-v7a
    IS64BIT=false
  fi
}

mktouch() {
  mkdir -p ${1%/*} 2>/dev/null
  [ -z $2 ] && touch $1 || echo $2 > $1
  chmod 644 $1
}

request_size_check() {
  reqSizeM=`du -ms "$1" | cut -f1`
}

request_zip_size_check() {
  reqSizeM=`unzip -l "$1" | tail -n 1 | awk '{ print int(($1 - 1) / 1048576 + 1) }'`
}

boot_actions() { return; }

handle_partition() {
    # if /system/vendor is a symlink, we need to move it out of $MODPATH/system
    # if /system/vendor is a normal directory, no special handling is needed.
    if [ ! -e $MODPATH/system/$1 ]; then
        # no partition found
        return;
    fi

    # we move the folder to / only if it is a native folder that is not a symlink
    if [ -d "/$1" ] && [ ! -L "/$1" ]; then
        ui_print "- Handle partition /$1"
        # we create a symlink if module want to access $MODPATH/system/$1
        # but it doesn't always work(ie. write it in post-fs-data.sh would fail because it is readonly)
        mv -f $MODPATH/system/$1 $MODPATH/$1 && ln -sf ../$1 $MODPATH/system/$1
    fi
}

check_sepolicy() {
  # Read-only syntax/semantic check through the published loader entry. It never
  # writes /sys/fs/selinux/load, so calling it changes no policy.
  [ -n "${1:-}" ] || return 1
  if ! command -v sepolicy >/dev/null 2>&1; then
    ui_print '! sepolicy checker is unavailable'
    return 1
  fi
  sepolicy check "$1"
}
mark_remove() {
  mkdir -p "${1%/*}" || return $?
  mknod "$1" c 0 0 || return $?
  chmod 0644 "$1"
}
install_module() {
api_level_arch_detect
export API ABI ABI32 ARCH IS64BIT
cd "$MODPATH" || return 1
if [ -f "$MODPATH/install.sh" ]; then
  [ -f "$LEGACY_INSTALLER" ] || abort 'Legacy installer helper is missing'
  /data/adb/maplesu/bin/busybox sh "$LEGACY_INSTALLER" "$MODPATH" "$ZIPFILE"
  return $?
fi
# KSU's installer extracts module.prop into TMPDIR before running customize.sh, and
# real modules read it from there (TEESimulator: grep_prop version "$TMPDIR/module.prop").
# Without this staging the version reads as empty and version-gated installers take the
# wrong branch. Staged, not moved: the module tree keeps its own copy.
[ -f "$MODDIR/module.prop" ] && cp -f "$MODDIR/module.prop" "$TMPDIR/module.prop"
MODID=$(grep_prop id "$TMPDIR/module.prop")
MODNAME=$(grep_prop name "$TMPDIR/module.prop")
MODAUTH=$(grep_prop author "$TMPDIR/module.prop")
# KSU applies defaults before customize.sh, and SKIPUNZIP installers own their
# extraction and permissions. A later recursive pass removes executable bits
# set by frameworks such as Vector and prevents their daemons from starting.
if ! grep -q '^SKIPUNZIP=1$' "$MODPATH/$SCRIPT" 2>/dev/null; then
  set_perm_recursive "$MODPATH" 0 0 0755 0644
  [ -d "$MODPATH/system/bin" ] && set_perm_recursive "$MODPATH/system/bin" 0 2000 0755 0755
  [ -d "$MODPATH/system/xbin" ] && set_perm_recursive "$MODPATH/system/xbin" 0 2000 0755 0755
  [ -d "$MODPATH/system/system_ext/bin" ] && set_perm_recursive "$MODPATH/system/system_ext/bin" 0 2000 0755 0755
  [ -d "$MODPATH/system/vendor" ] && set_perm_recursive "$MODPATH/system/vendor" 0 2000 0755 0755 u:object_r:vendor_file:s0
fi
# Module installers use explicit abort/exit and ordinary false condition tests.
# Inherit Magisk/KSU source semantics instead of imposing errexit on their code.
set +e
_script_status=0
if [ -f "$MODPATH/$SCRIPT" ]; then
  . "$MODPATH/$SCRIPT"
  _script_status=$?
fi
set -e
[ "$_script_status" -eq 0 ] || exit "$_script_status"

# Preserve the explicit callback supported by earlier MapleSU installers.
if command -v set_permissions >/dev/null 2>&1; then
  set_permissions || exit $?
fi
for TARGET in ${REPLACE:-}; do
  mkdir -p "$MODPATH$TARGET" || return 1
  touch "$MODPATH$TARGET/.replace" || return 1
done
for TARGET in ${REMOVE:-}; do
  mark_remove "$MODPATH$TARGET" || return $?
done
handle_partition vendor
handle_partition system_ext
handle_partition product
handle_partition odm

# The module opts in; an archive's pre-existing marker grants no hot request.
if [ "${MODULE_HOT_INSTALL_REQUEST:-false}" = true ]; then
  printf '%s\n' "${MODULE_HOT_RUN_SCRIPT:-}" > "$MODPATH/.ksu_hot_install" || exit $?
else
  rm -f "$MODPATH/.ksu_hot_install" || exit $?
fi

rm -f "$MODPATH/customize.sh" "$MODPATH/README.md"
rm -rf "$TMPDIR"
}

# Like KSU get_install_script(), the selected metainstall owns the call to
# install_module. It can wrap the default installer, or provide its own installer.
# A metamodule update always uses the default installer to avoid calling itself.
_meta=/data/adb/metamodule
_is_meta=$(grep_prop metamodule "$MODPATH/module.prop" | tr -d '[:space:]' | tr '[:upper:]' '[:lower:]')
case "$_is_meta" in
  1|true) install_module ;;
  *)
    if [ -f "$_meta/metainstall.sh" ]; then
      for _marker in disable remove update; do
        [ ! -e "$_meta/$_marker" ] || abort "METAMODULE_INSTALL_BLOCKED: $_marker"
      done
      cd "$MODPATH" || exit 70
      . "$_meta/metainstall.sh"
    else
      install_module
    fi
    ;;
esac
