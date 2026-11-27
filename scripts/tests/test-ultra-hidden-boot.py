"""Exercise the selected boot script's hidden-recovery gates without a device."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / 'manager/src/main/assets/maplesu/modules/boot.sh'
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--out', type=Path, required=True)
a = p.parse_args()
out = a.out.resolve()
out.mkdir(parents=True, exist_ok=True)
source = SOURCE.read_text(encoding='utf-8')
functions = source[source.index('recovery_line() {'):source.index('policies() {')]
functions = functions.replace('/data/adb', '/output/adb').replace('/system/bin/sh', '/bin/sh')
(out / 'functions.sh').write_text(functions, encoding='utf-8', newline='\n')
script = r'''
set -eu
mkdir -p /output/wrappers
cat > /output/wrappers/stat <<'EOF'
#!/bin/sh
printf 'stat\n' >> /output/trace
exec /usr/bin/stat "$@"
EOF
chmod 700 /output/wrappers/stat
export PATH=/output/wrappers:$PATH
. /output/functions.sh
working=/output/adb/maplesu
record() { printf '%s:%s\n' "$1" "$2" >> /output/records; }
reset() {
  rm -rf /output/adb
  mkdir -p "$working/recovery" "$working/ultra-identity"
  chmod 700 /output/adb "$working" "$working/recovery" "$working/ultra-identity"
  printf 'workMode=LITE\n' > "$working/recovery/webui.conf"
  printf 'printf "started\\n" >> /output/trace\nexit 0\n' > "$working/recovery/parasite-start.sh"
  chmod 700 "$working/recovery/parasite-start.sh"
  : > /output/trace; : > /output/records
}
quiet() { resume_hidden_recovery; test ! -s /output/trace; test ! -s /output/records; }
started() {
  resume_hidden_recovery
  test "$(grep -c '^started$' /output/trace)" = 1
  grep -qx '0:recovery-start' /output/records
}
reset; quiet
printf '0\n' > "$working/lite_hidden"; quiet
printf '1\nextra\n' > "$working/lite_hidden"; quiet
rm "$working/lite_hidden"
printf 'pending:invalid\n' > "$working/recovery/manager-restore.ticket"; quiet
printf 'pending:aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\n' > "$working/recovery/manager-restore.ticket"; started
reset; printf '1\n' > "$working/lite_hidden"; started
reset; printf '1\n' > "$working/lite_hidden"; touch "$working/ultra-identity/disabled"; quiet
reset; printf '1\n' > "$working/lite_hidden"; touch "$working/recovery/webui.paused"; quiet
reset; printf '1\n' > "$working/lite_hidden"; printf 'workMode=NORMAL\n' > "$working/recovery/webui.conf"; quiet
reset; printf '1\n' > "$working/lite_hidden"; printf 'workMode=LITE\nworkMode=LITE\n' > "$working/recovery/webui.conf"; quiet
reset; printf '1\n' > "$working/lite_hidden"; chmod 777 "$working/recovery/parasite-start.sh"
resume_hidden_recovery; ! grep -qx started /output/trace; grep -qx '73:recovery-start' /output/records
reset; printf '1\n' > "$working/lite_hidden"; mv "$working/recovery/parasite-start.sh" "$working/recovery/other.sh"
ln -s other.sh "$working/recovery/parasite-start.sh"
resume_hidden_recovery; ! grep -qx started /output/trace; grep -qx '73:recovery-start' /output/records
reset; printf '1\n' > "$working/lite_hidden"; printf 'exit 23\n' > "$working/recovery/parasite-start.sh"
resume_hidden_recovery; grep -qx '23:recovery-start' /output/records
printf 'PASS 13 selected hidden-boot gate cases\n'
'''
argv = ['docker', 'run', '--rm', '--pull', 'never', '--network', 'none', '--cap-drop', 'ALL',
        '--security-opt', 'no-new-privileges', '--mount', f'type=bind,source={out},target=/output',
        'ghcr.io/ylarod/ddk-min:android15-6.6-20260828', 'sh', '-c', script]
r = subprocess.run(argv, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=55)
report = dict(passed=r.returncode == 0, exitCode=r.returncode, stdout=r.stdout, stderr=r.stderr,
              sourceSha256=hashlib.sha256(SOURCE.read_bytes()).hexdigest(),
              eventCallPresent='stage_scripts "$event"\n      resume_hidden_recovery ;;' in source,
              scope='Actual selected recovery functions; device paths remapped, real shell/file/stat/exec. No kernel, daemon HTTP or device boot.')
(out / 'result.json').write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
print(r.stdout + r.stderr)
raise SystemExit(r.returncode or (not report['eventCallPresent']))
