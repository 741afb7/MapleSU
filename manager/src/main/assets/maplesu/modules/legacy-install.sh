#!/system/bin/sh
# MapleSU legacy module installer bridge.
#
# Executed as: <this> <module-dir> <archive>
#
# Reproduces the legacy `install.sh` contract from the pinned KSU userspace
# (installer.sh:392-410) instead of treating install.sh as a standalone script:
#
#   unzip -oj "$ZIPFILE" module.prop install.sh uninstall.sh 'common/*' -d $TMPDIR
#   . $TMPDIR/install.sh
#   print_modname
#   on_install
#   $SKIPMOUNT  && touch $MODPATH/skip_mount
#   $PROPFILE   && cp -af $TMPDIR/system.prop    $MODPATH/system.prop
#   $POSTFSDATA && cp -af $TMPDIR/post-fs-data.sh $MODPATH/post-fs-data.sh
#   $LATESTARTSERVICE && cp -af $TMPDIR/service.sh $MODPATH/service.sh
#   set_permissions
#
# The three callbacks are defined *by install.sh itself* (Magisk legacy contract),
# which is why this must source it rather than exec it. Exiting 0 without having
# run a defined callback would silently accept a module that never installed, so a
# missing callback is reported as a failure.
set -u

module_dir="${1:-}"
archive="${2:-}"
if [ -z "$module_dir" ] || [ -z "$archive" ]; then
  printf 'maplesu-legacy-installer: usage: legacy-install.sh <module-dir> <archive>\n' >&2
  exit 64
fi

script="$module_dir/install.sh"
if [ ! -f "$script" ]; then
  printf 'maplesu-legacy-installer: no install.sh in %s\n' "$module_dir" >&2
  exit 66
fi

# Installer-scope variables, as installer.sh:378-383 + 486-493 set them.
MODPATH="$module_dir"
export MODPATH
ZIPFILE="$archive"
export ZIPFILE
TMPDIR="$module_dir/.maplesu-tmp"
mkdir -p "$TMPDIR" || exit 70
export TMPDIR
NVBASE=/data/adb
MODULEROOT=${MODPATH%/*}
BOOTMODE=true
OUTFD=1
export BOOTMODE OUTFD NVBASE MODULEROOT

MAPLESU_BIN=${MAPLESU_BIN:-/data/adb/maplesu/bin}
export PATH="${PATH:-/system/bin:/system/xbin}:$MAPLESU_BIN"

# Same module-script compatibility environment as the modern installer.
export MAPLESU=true
export MAPLESU_BACKEND=legacy_installer
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
export ASH_STANDALONE=1
unset MAGISK
export MAGISK_VER=25.2 MAGISK_VER_CODE=25200
export POSTFSDATAD="$NVBASE/post-fs-data.d" SERVICED="$NVBASE/service.d"
module_id="${module_dir##*/}"
case "$module_id" in
  ''|*/*|.|..) ;;
  *) export MAPLESU_MODULE="$module_id" KSU_MODULE="$module_id" ;;
esac

# Legacy flags default to false, exactly like installer.sh:486-493.
SKIPMOUNT=false
PROPFILE=false
POSTFSDATA=false
LATESTARTSERVICE=false
REPLACE=""
REMOVE=""

ui_print() { printf '%s\n' "$*"; }
# Legacy callbacks run in this shell and need the same XZ support as customize.sh.
unzip() { "$MAPLESU_BIN/busybox" unzip "$@"; }
abort() {
  printf '! %s\n' "$*" >&2
  # installer.sh:55-61: abort removes the module path and the temp dir.
  rm -rf "$MODPATH" "$TMPDIR"
  exit 1
}

# Magisk-compatible permission helpers. A context argument applies an existing
# SELinux type only; declaring new types belongs to the module policy loader.
set_perm() {
  [ "$#" -eq 4 ] || [ "$#" -eq 5 ] || abort "set_perm requires target uid gid mode [context]"
  chown "$2:$3" "$1" || return 1
  chmod "$4" "$1" || return 1
  chcon "${5:-u:object_r:system_file:s0}" "$1" || return 1
  return 0
}
set_perm_recursive() {
  [ "$#" -eq 5 ] || [ "$#" -eq 6 ] || abort "set_perm_recursive requires target uid gid dirmode filemode [context]"
  chown -R "$2:$3" "$1" || return 1
  find "$1" -type d -exec chmod "$4" '{}' ';' || return 1
  find "$1" -type f -exec chmod "$5" '{}' ';' || return 1
  find "$1" -exec chcon "${6:-u:object_r:system_file:s0}" '{}' ';' || return 1
  return 0
}
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


mark_remove() {
  mkdir -p "${1%/*}" || return $?
  mknod "$1" c 0 0 || return $?
  chmod 0644 "$1"
}

# Default set_permissions callback when install.sh does not define one: the same
# default recursive modes installer.sh applies for modern modules.
default_permissions() {
  set_perm_recursive "$MODPATH" 0 0 0755 0644
  [ -d "$MODPATH/system/bin" ] && set_perm_recursive "$MODPATH/system/bin" 0 2000 0755 0755
  [ -d "$MODPATH/system/xbin" ] && set_perm_recursive "$MODPATH/system/xbin" 0 2000 0755 0755
  [ -d "$MODPATH/system/system_ext/bin" ] && set_perm_recursive "$MODPATH/system/system_ext/bin" 0 2000 0755 0755
  [ -d "$MODPATH/system/vendor" ] && set_perm_recursive "$MODPATH/system/vendor" 0 2000 0755 0755 u:object_r:vendor_file:s0
  return 0
}

cd "$module_dir" || exit 70
# KSU flattens common/* into its installer TMPDIR before sourcing install.sh.
# Legacy on_install callbacks and PROPFILE/POSTFSDATA/LATESTARTSERVICE read there.
unzip -oj "$ZIPFILE" module.prop install.sh uninstall.sh 'common/*' -d "$TMPDIR" >&2
# KSU does not impose nounset on module code; optional manager variables may be
# absent. The selected script keeps control of its own shell options.
set +u
# shellcheck disable=SC1090
api_level_arch_detect
export API ABI ABI32 ARCH IS64BIT
MODID=$(grep_prop id "$TMPDIR/module.prop")
MODNAME=$(grep_prop name "$TMPDIR/module.prop")
MODAUTH=$(grep_prop author "$TMPDIR/module.prop")
. "$TMPDIR/install.sh" || exit $?

# Callbacks, in installer.sh order. install.sh defines them; a missing definition
# means the module did not follow the legacy contract, which must fail loudly.
if command -v print_modname >/dev/null 2>&1; then print_modname; fi
if ! command -v on_install >/dev/null 2>&1; then
  printf 'maplesu-legacy-installer: install.sh does not define on_install\n' >&2
  exit 68
fi
on_install || exit $?

# Flag-driven file publication, installer.sh:402-407.
[ -f "$TMPDIR/uninstall.sh" ] && cp -af "$TMPDIR/uninstall.sh" "$MODPATH/uninstall.sh"
if [ "${SKIPMOUNT:-false}" = true ]; then touch "$MODPATH/skip_mount"; fi
if [ "${PROPFILE:-false}" = true ] && [ -f "$TMPDIR/system.prop" ]; then
  cp -af "$TMPDIR/system.prop" "$MODPATH/system.prop"
fi
cp -af "$TMPDIR/module.prop" "$MODPATH/module.prop" || exit $?
if [ "${POSTFSDATA:-false}" = true ] && [ -f "$TMPDIR/post-fs-data.sh" ]; then
  cp -af "$TMPDIR/post-fs-data.sh" "$MODPATH/post-fs-data.sh"
fi
if [ "${LATESTARTSERVICE:-false}" = true ] && [ -f "$TMPDIR/service.sh" ]; then
  cp -af "$TMPDIR/service.sh" "$MODPATH/service.sh"
fi

ui_print "- Setting permissions"
if command -v set_permissions >/dev/null 2>&1; then
  set_permissions || exit $?
else
  default_permissions || exit $?
fi

for TARGET in $REPLACE; do
  mkdir -p "$MODPATH$TARGET" || exit 1
  touch "$MODPATH$TARGET/.replace" || exit 1
done
for TARGET in ${REMOVE:-}; do
  mark_remove "$MODPATH$TARGET" || exit $?
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

rm -rf "$TMPDIR"
exit 0
