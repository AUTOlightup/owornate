"""Exercise the real widget host script with simulated Android commands.

python tests/widget_host_test.py --bash "C:/Program Files/Git/bin/bash.exe"

This verifies read-only checks, whitelist writes, backup/permission preservation,
and idempotent repair on a host filesystem. It does not touch a watch.
"""
from pathlib import Path
import argparse
import hashlib
import os
import shutil
import stat
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('--bash', default=shutil.which('bash') or 'bash')
args = parser.parse_args()
project = Path(__file__).resolve().parents[1]
work = Path.cwd() / 'work'
work.mkdir(exist_ok=True)
count = 0
PKG = 'dev.codex.watchworkshop'


def check(condition, label):
    global count
    assert condition, label
    count += 1

v11_source = (project / 'widget-host/WatchHide-Launcher-Hook-v11.js').read_bytes()
v12_source = (project / 'widget-host/WatchHide-Launcher-Hook-v12.js').read_bytes()
v13_source = (project / 'widget-host/WatchHide-Launcher-Hook-v13.js').read_bytes()
V12_SHA = '4bc93055211b1c070155b45808955b6e41380c51edc63010ba8f0f759c508ac0'
V13_SHA = 'd0d3406f1ce940f20f2089b2962c41d5eb0830249cd781887f37fecc90c088d7'
check(v12_source.startswith(v11_source), 'v12 preserves the complete v11 byte prefix')
check(hashlib.sha256(v12_source).hexdigest() == V12_SHA, 'v12 remains the released v12 bytes')
check(hashlib.sha256(v13_source).hexdigest() == V13_SHA, 'v13 matches the integrated payload hash')
check(v13_source.startswith(v11_source), 'v13 preserves the complete v11 byte prefix')
check(v13_source == v11_source + b'\n'
      + (project / 'widget-host/capture-controls.js').read_bytes() + b'\n'
      + (project / 'widget-host/capture-indicator.js').read_bytes(),
      'v13 exactly matches v11 + current controls + indicator')


with tempfile.TemporaryDirectory(prefix='widget-host-', dir=work) as scratch:
    root = Path(scratch)
    assert root.resolve().parent == work.resolve()
    base = root / 'base'
    source = root / 'source'
    bin_dir = root / 'bin'
    system = root / 'system'
    proc = root / 'proc' / '424242'
    for directory in (base, source, bin_dir, system, proc):
        directory.mkdir(parents=True, exist_ok=True)

    shutil.copyfile(project / 'widget-host' / 'WatchHide-Launcher-Hook-v13.js', source / 'WatchHide-Launcher-Hook-v13.js')
    shutil.copyfile(project / 'widget-host' / 'WatchHide-Launcher-Hook-v13.js', base / 'hook.js')
    shutil.copyfile(project / 'widget-host' / 'WatchHide-Launcher-Hook-v10.js', root / 'hook-v10.js')
    shutil.copyfile(project / 'assets' / 'monet' / 'theme.jar', root / 'shared-theme.jar')
    (base / 'watchhide-bootstrap.sh').write_text('#!/bin/sh\necho supervisor_pid=123\n', encoding='utf-8', newline='\n')
    (proc / 'cmdline').write_bytes(b'com.heytap.wearable.launcher' + b'\0' * 48)
    (root / 'pid').write_text('424242\n', encoding='utf-8', newline='\n')

    def write_cmd(name, body):
        path = bin_dir / name
        path.write_text('#!/bin/sh\n' + body, encoding='utf-8', newline='\n')
        path.chmod(0o755)

    write_cmd('id', 'echo 0\n')
    write_cmd('getprop', 'case "$1" in\nro.product.model) echo OW20W1;;\nro.build.display.id) echo OW20W1_11_A.92;;\nesac\n')
    write_cmd('pidof', 'cat "$SIM_ROOT/pid" 2>/dev/null || true\n')
    write_cmd('sleep', 'exit 0\n')
    write_cmd('chown', 'exit 0\n')
    write_cmd('am', 'echo "am $*" >> "$SIM_ROOT/actions"\n')
    write_cmd('settings', 'case "$1" in\nget) cat "$SIM_ROOT/settings-$3" 2>/dev/null || echo null;;\nput) printf "%s\\n" "$4" > "$SIM_ROOT/settings-$3";;\nesac\n')

    control = (project / 'widget-host' / 'widget-manage.sh').read_text(encoding='utf-8')
    check(V13_SHA in control, 'widget script pins the v13 payload')
    control = control.replace('BASE=/data/local/tmp/watchhide', 'BASE=./base')
    control = control.replace('SOURCE=/data/user/0/dev.codex.watchworkshop/files/widget-host', 'SOURCE=./source')
    control = control.replace('SYS=/data/system/sys_process_switch_list.xml', 'SYS=./system/sys_process_switch_list.xml')
    control = control.replace('/data/local/tmp/watchface-theme/theme.jar', './shared-theme.jar')
    control = control.replace('/proc/', './proc/')
    killer = (
        'kill() {\n'
        '  echo "kill $*" >> "$SIM_ROOT/actions"\n'
        '  echo 424243 > "$SIM_ROOT/pid"\n'
        '  touch "$SIM_ROOT/base/hook.js"\n'
        '  printf \'pid=424243 mode=ok at=0\\n\' > "$SIM_ROOT/base/injected.marker"\n'
        '}\n'
    )
    control = 'export PATH="$PWD/bin:$PATH"\nexport SIM_ROOT="$PWD"\n' + killer + control
    (root / 'control.sh').write_text(control, encoding='utf-8', newline='\n')

    def snapshot():
        result = {}
        for path in sorted(root.rglob('*')):
            rel = path.relative_to(root).as_posix()
            if path.is_dir():
                result[rel + '/'] = None
            else:
                result[rel] = (path.read_bytes(), stat.S_IMODE(path.stat().st_mode))
        return result

    def run(action, ok=True):
        p = subprocess.run(
            [args.bash, '--noprofile', '--norc', 'control.sh', action],
            cwd=root, capture_output=True, text=True, timeout=30)
        check((p.returncode == 0) == ok,
              f'{action}: rc={p.returncode} out={p.stdout!r} err={p.stderr!r}')
        return p.stdout.strip().splitlines()

    def sys_path():
        return system / 'sys_process_switch_list.xml'

    def backup_path():
        return base / 'sys_process_switch_list.before-owornate.xml'

    def tmp_path():
        return base / 'sys_process_switch_list.owornate-new'

    def write_sys(entries, closing=True):
        lines = ['<?xml version="1.0" encoding="utf-8"?>', '<process_switch_list>']
        lines.extend(f'  <allow_process>{entry}</allow_process>' for entry in entries)
        if closing:
            lines.append('</process_switch_list>')
        sys_path().write_bytes(('\n'.join(lines) + '\n').encode())
        os.chmod(sys_path(), 0o640)

    def bash_mode(path):
        p = subprocess.run(
            [args.bash, '--noprofile', '--norc', '-c', f'stat -c %a "{path.as_posix()}"'],
            cwd=root, capture_output=True, text=True, timeout=10)
        check(p.returncode == 0, f'stat failed: {p.stderr}')
        return p.stdout.strip()

    def set_ready(pid='424242'):
        shutil.copyfile(project / 'widget-host' / 'WatchHide-Launcher-Hook-v13.js', base / 'hook.js')
        (root / 'pid').write_text(pid + '\n', encoding='utf-8', newline='\n')
        (base / 'injected.marker').write_text(f'pid={pid} mode=ok at=0\n', encoding='utf-8', newline='\n')

    def check_read_only(expected, label):
        before = snapshot()
        lines = run('check')
        check(lines == expected, f'{label}: {lines}')
        check(snapshot() == before, f'{label}: fixtures changed')
        check(not backup_path().exists(), f'{label}: check created a backup')
        check(not tmp_path().exists(), f'{label}: check created a temp file')

    entries = ['com.example.one', 'com.example.two']

    check((project / 'widget-host' / 'widget-manage.sh').read_bytes()
          == (project / 'assets' / 'widget-host' / 'widget-manage.sh').read_bytes(),
          'APK asset mirrors the widget script')

    # check: host + whitelist, with no write of any kind.
    write_sys(entries + [PKG])
    set_ready()
    check_read_only(['ready', 'present'], 'ready/present')
    before = snapshot()
    run('check')
    check(snapshot() == before, 'second check kept fixtures unchanged')

    write_sys(entries)
    (base / 'injected.marker').unlink()
    check_read_only(['installed', 'missing'], 'installed/missing')

    sys_path().unlink()
    shutil.copyfile(root / 'hook-v10.js', base / 'hook.js')
    check_read_only(['available', 'unavailable'], 'available/unavailable')

    write_sys(entries, closing=False)
    set_ready()
    check_read_only(['ready', 'unavailable'], 'malformed/unavailable')

    # keepalive: append once, preserve every original entry, mode, and a backup.
    write_sys(entries)
    original = sys_path().read_bytes()
    original_mode = bash_mode(sys_path())
    lines = run('keepalive')
    check(lines == ['added'], f'keepalive added: {lines}')
    text = sys_path().read_text(encoding='utf-8')
    check(text.count(f'<allow_process>{PKG}</allow_process>') == 1, 'package added once')
    check(all(f'<allow_process>{entry}</allow_process>' in text for entry in entries), 'original entries preserved')
    check(text.index(entries[0]) < text.index(entries[1]) < text.index(PKG), 'original order preserved')
    check(backup_path().read_bytes() == original, 'backup is the original bytes')
    check(bash_mode(backup_path()) == original_mode, 'backup mode preserved')
    check(bash_mode(sys_path()) == original_mode, 'whitelist mode preserved')
    check(not tmp_path().exists(), 'keepalive left no temp file')

    after_first = sys_path().read_bytes()
    backup_first = backup_path().read_bytes()
    lines = run('keepalive')
    check(lines == ['present'], f'second keepalive: {lines}')
    check(sys_path().read_bytes() == after_first, 'repeated keepalive is idempotent')
    check(backup_path().read_bytes() == backup_first, 'backup unchanged on repeat')
    check(not tmp_path().exists(), 'repeat left no temp file')

    lines = run('repair')
    check(lines == ['ready', 'present'], f'repair present: {lines}')
    check(not (root / 'actions').exists(), 'repair does not reload the Launcher')
    check(sys_path().read_bytes() == after_first, 'repair does not rewrite a present entry')
    check(backup_path().read_bytes() == backup_first, 'repair kept the backup')
    check(not tmp_path().exists(), 'repair left no temp file')

    # repair from missing: add on first call, then stay byte-identical.
    sys_path().write_bytes(original)
    os.chmod(sys_path(), 0o640)
    backup_path().unlink()
    original_mode = bash_mode(sys_path())
    lines = run('repair')
    check(lines == ['ready', 'added'], f'repair added: {lines}')
    repaired = sys_path().read_bytes()
    check(repaired.count(f'<allow_process>{PKG}</allow_process>'.encode()) == 1, 'repair added once')
    check(backup_path().read_bytes() == original, 'repair backed up the original')
    check(bash_mode(sys_path()) == original_mode, 'repair preserved mode')
    check(not tmp_path().exists(), 'repair left no temp file')
    lines = run('repair')
    check(lines == ['ready', 'present'], f'repeated repair: {lines}')
    check(sys_path().read_bytes() == repaired, 'repeated repair is idempotent')
    check(backup_path().read_bytes() == original, 'repeated repair kept the backup')
    check(not tmp_path().exists(), 'repeated repair left no temp file')

    # repair unavailability is explicit and does not invent a file.
    sys_path().unlink()
    backup_path().unlink()
    lines = run('repair', ok=False)
    check(any('KEEP_UNAVAILABLE' in line for line in lines), f'repair unavailable: {lines}')
    check(not sys_path().exists(), 'repair did not create the system file')
    check(not backup_path().exists(), 'repair did not create a backup')
    check(not tmp_path().exists(), 'repair did not create a temp file')

    # v11 and v12 remain known; v13 is the install target.
    shutil.copyfile(project / 'widget-host' / 'WatchHide-Launcher-Hook-v11.js', base / 'hook.js')
    (base / 'injected.marker').write_text('pid=424242 mode=ok at=0\n', encoding='utf-8', newline='\n')
    write_sys(entries + [PKG])
    check(run('check') == ['ready', 'present'], 'v11 remains a known shared host')
    check(run('capture-status') == ['available'], 'v11 has no capture controls')
    check(run('breeno-hide') == ['hidden'], 'v11 breeno hide preserved')
    check(run('breeno-show') == ['shown'], 'v11 breeno show preserved')

    shutil.copyfile(project / 'widget-host' / 'WatchHide-Launcher-Hook-v12.js', base / 'hook.js')
    (base / 'injected.marker').write_text('pid=424242 mode=ok at=0\n', encoding='utf-8', newline='\n')
    check(run('check') == ['ready', 'present'], 'v12 remains a known shared host')
    check(run('capture-enable') == ['enabled'], 'v12 capture can be enabled')
    check(run('capture-status') == ['ready'], 'v12 capture status ready')
    check(run('capture-disable') == ['disabled'], 'v12 capture can be disabled')
    check(run('capture-status') == ['disabled'], 'v12 capture status disabled')
    check(run('breeno-hide') == ['hidden'], 'v12 breeno hide preserved')
    check(run('breeno-show') == ['shown'], 'v12 breeno show preserved')

    # Explicit install upgrades a compatible v12 host to v13.
    if (base / 'hook-before-v13.js').exists():
        (base / 'hook-before-v13.js').unlink()
    lines = run('install')
    check(lines == ['ready', 'present'], f'v12 upgrade: {lines}')
    check((base / 'hook.js').read_bytes() == v13_source, 'v12 install upgraded to v13')
    check((base / 'hook-before-v13.js').read_bytes()
          == (project / 'widget-host' / 'WatchHide-Launcher-Hook-v12.js').read_bytes(),
          'v12 upgrade kept the prior payload')
    check(run('breeno-hide') == ['hidden'], 'v13 breeno hide')
    check(run('breeno-show') == ['shown'], 'v13 breeno show')

    # A deployed v13 revision remains usable and upgrades to the current bytes.
    # Substitute its digest in the fixture rather than shipping old payloads.
    old_v13 = b'previous supported v13 revision'
    old_digest = hashlib.sha256(old_v13).hexdigest()
    for previous in ('aff84e9105a66738234398db5e5beef4fd67b384a804308479cfda472ef349a1',
                     'fdbf56b324eb29fd37f9d92644b051194503024ea34fcca4b1ddcdd9ecb3d46a',
                     '46b4ae970215c889e36524c3969b66e45bba45100afd973daae07947f531ea19'):
        check(previous in control, 'known deployed v13 revision is recognized')
        (root / 'control.sh').write_text(control.replace(previous, old_digest),
                                        encoding='utf-8', newline='\n')
        (base / 'hook.js').write_bytes(old_v13)
        (root / 'pid').write_text('424242\n', encoding='utf-8', newline='\n')
        (base / 'injected.marker').write_text('pid=424242 mode=ok at=0\n',
                                             encoding='utf-8', newline='\n')
        (base / 'hook-before-v13.js').unlink(missing_ok=True)
        check(run('capture-enable') == ['enabled'], 'previous v13 can still be enabled')
        before = snapshot()
        check(run('capture-status') == ['ready'], 'previous v13 status remains ready')
        check(snapshot() == before, 'previous v13 status is read-only')
        check(run('capture-install') == ['ready', 'present'], 'previous v13 upgrades normally')
        check((base / 'hook.js').read_bytes() == v13_source, 'previous v13 bytes replaced')
        check((base / 'hook-before-v13.js').read_bytes() == old_v13, 'previous v13 backed up')
    (root / 'control.sh').write_text(control, encoding='utf-8', newline='\n')

    # install: v10 -> v13, one reload, then keepalive.
    shutil.copyfile(root / 'hook-v10.js', base / 'hook.js')
    if (base / 'hook-before-v13.js').exists():
        (base / 'hook-before-v13.js').unlink()
    (base / 'injected.marker').unlink()
    (root / 'pid').write_text('424242\n', encoding='utf-8', newline='\n')
    write_sys(entries)
    if backup_path().exists():
        backup_path().unlink()
    actions = root / 'actions'
    if actions.exists():
        actions.unlink()
    lines = run('install')
    check(lines == ['ready', 'added'], f'install added: {lines}')
    check((base / 'hook.js').read_bytes() == (source / 'WatchHide-Launcher-Hook-v13.js').read_bytes(), 'install deployed v13')
    check((base / 'hook-before-v13.js').read_bytes() == (root / 'hook-v10.js').read_bytes(), 'install kept the v10 backup')
    check(PKG in sys_path().read_text(encoding='utf-8'), 'install added the whitelist entry')
    check('kill -9 424242' in actions.read_text(encoding='utf-8'), 'install reloaded the Launcher')
    check('am start -a android.intent.action.MAIN -c android.intent.category.HOME -p com.heytap.wearable.launcher' in actions.read_text(encoding='utf-8'), 'install requested HOME to restart the background Launcher')
    check(not (base / 'hook.js.widgets-new').exists(), 'install left no payload temp file')

    # A successful hook attach stays successful when keepalive cannot run.
    set_ready()
    sys_path().unlink()
    if backup_path().exists():
        backup_path().unlink()
    if actions.exists():
        actions.unlink()
    lines = run('install')
    check(lines == ['ready', 'unavailable'], f'install with keepalive failure: {lines}')
    check((base / 'hook.js').read_bytes() == (source / 'WatchHide-Launcher-Hook-v13.js').read_bytes(), 'keepalive failure kept v13')
    check(not sys_path().exists(), 'keepalive failure did not create the system file')
    check(not backup_path().exists(), 'keepalive failure did not create a backup')
    check('kill -9 424242' in actions.read_text(encoding='utf-8'), 'keepalive failure did not undo the reload')

    # Capture attachment updates only the Hook, never the process whitelist.
    shutil.copyfile(root / 'hook-v10.js', base / 'hook.js')
    (root / 'pid').write_text('424242\n', encoding='utf-8', newline='\n')
    if (base / 'injected.marker').exists():
        (base / 'injected.marker').unlink()
    write_sys(entries)
    whitelist_before = sys_path().read_bytes()
    check(run('capture-status') == ['available'], 'old hook has no capture buttons')
    lines = run('capture-install')
    check(lines == ['ready', 'missing'], f'capture install: {lines}')
    check((base / 'hook.js').read_bytes() == v13_source, 'capture install deployed v13')
    check(sys_path().read_bytes() == whitelist_before, 'capture install never changes whitelist')
    check(run('capture-status') == ['ready'], 'capture buttons confirmed after attachment')
    actions_before=actions.read_bytes()
    check(run('capture-disable') == ['disabled'], 'capture module can be disabled')
    check(run('capture-status') == ['disabled'], 'capture deployment retained while disabled')
    check(run('capture-enable') == ['enabled'], 'capture module can be enabled again')
    check(run('capture-status') == ['ready'], 'capture re-enable confirmed')
    check(actions.read_bytes() == actions_before, 'module switches never reload Launcher')
    check(sys_path().read_bytes() == whitelist_before, 'module switches never write whitelist')

    print(f'Widget host: {count}/{count} checks passed (simulated Android)')

