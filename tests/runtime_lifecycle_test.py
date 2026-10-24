"""Exercise the self-owned v4 runtime lifecycle on a simulated Android host.

python tests/runtime_lifecycle_test.py --bash /path/to/bash

The test copies the production manager into a temporary private-root fixture,
rewrites only fixed device paths and public fixture pins, and invokes it with
stub platform and supervisor commands. It never touches a watch or injects into
a real process.
"""
from pathlib import Path
import argparse
import hashlib
import itertools
import json
import os
import re
import shutil
import stat
import subprocess
import tempfile


def default_bash():
    candidates = [
        shutil.which('bash'),
        r'C:\Program Files\Git\bin\bash.exe',
        '/bin/bash',
    ]
    for value in candidates:
        if value and Path(value).exists():
            return value
    return 'bash'


parser = argparse.ArgumentParser()
parser.add_argument('--bash', default=default_bash())
parser.add_argument('--skip-orders', action='store_true', help=argparse.SUPPRESS)
parser.add_argument('--section',
                    choices=('all','orders','guards','lifecycle','thirdparty','v3migration',
                             'idconflict','wrappers'),
                    default='all')
args = parser.parse_args()
project = Path(__file__).resolve().parents[1]
work = Path.cwd() / 'work'
work.mkdir(exist_ok=True)
count = 0

FEATURES = ('monet', 'widgets', 'capture')
OFFICIAL_IDS = ('dev.owornate.monet', 'dev.owornate.widgets', 'dev.owornate.capture')
PKG = 'com.heytap.wearable.launcher'
APP = 'dev.codex.watchworkshop'
CORE = b'fixture public runtime core\n'
COMMIT = b'fixture public runtime commit\n'
SERVICE = b'#!/bin/sh\nexit 0\n'
THEME = b'PK\x03\x04fixture new theme helper\n'
THEME2 = b'PK\x03\x04fixture replacement theme helper\n'
FRIDA = b'fixture frida inject payload\n'
OLD_HOOK = b'fixture old standalone hook\n'
OLD_SERVICE = b'#!/bin/sh\n# old monet service\nexit 0\n'
OLD_THEME = b'PK\x03\x04fixture released v2 theme\n'
V2_RUNTIME = b'fixture current released v2 runtime\n'
V2_SERVICE = b'#!/bin/sh\n# current released v2 service\nexit 0\n'
CODE24_RUNTIME = b'fixture known code24 v2 hook\n'
CODE24_SERVICE = b'#!/bin/sh\n# known code24 v2 service\nexit 0\n'
V3_CORE = b'fixture released v3 public runtime core\n'
V3_COMMIT = b'fixture released v3 public runtime commit\n'
V3_SERVICE = b'#!/bin/sh\n# fixture released v3 service\nexit 0\n'
KNOWN_SHARED = b'fixture known shared v13\n'
PLUGIN = {
    # Deliberately no trailing newline: the stage boundary must supply `\n;\n`.
    'monet': b'// fixture monet plugin\nglobalThis.monet = true;',
    'widgets': b'// fixture widgets plugin\nglobalThis.widgets = true;\n',
    'capture': b'// fixture capture plugin\nglobalThis.capture = true;\n',
}


def wrapped_third(plugin_id, body):
    return (f"Java.perform(function(){{globalThis.__watchWorkshop.register('{plugin_id}',"
            f"function(plugin){{\n").encode() + body + b'\n;\n});});\n'


def combined(core, payloads, commit, third=None):
    parts = [core, *payloads]
    third = third or {}
    parts.extend(wrapped_third(plugin_id, third[plugin_id]) for plugin_id in sorted(third))
    parts.append(commit)
    return b'\n;\n'.join(parts)


def check(condition, label):
    global count
    assert condition, label
    count += 1


def sha(data):
    return hashlib.sha256(data).hexdigest()


def assignment(text, name):
    match = re.search(rf'(?m)^{re.escape(name)}=(\S+)\s*$', text)
    assert match, f'missing {name} assignment'
    return match.group(1)


def replace_assignment(text, name, value):
    changed, total = re.subn(rf'(?m)^{re.escape(name)}=\S+\s*$', f'{name}={value}', text)
    assert total == 1, f'{name} assignment count={total}'
    return changed


manager_source = (project / 'standalone/runtime-manage.sh').read_text(encoding='utf-8')
widget_source = (project / 'widget-host/widget-manage.sh').read_text(encoding='utf-8')
module_source = (project / 'standalone/module-manage.sh').read_text(encoding='utf-8')

core_pin = assignment(manager_source, 'CORE_SHA')
commit_pin = assignment(manager_source, 'COMMIT_SHA')
service_pin = assignment(manager_source, 'SERVICE_SHA')
runtime_pin = assignment(manager_source, 'RUNTIME_SHA')
check(assignment(manager_source, 'V3_CORE_SHA') ==
      '3a403add0b743cd8402bd5011bca0275024daf9197662de1e15fdc1054bd6ee6',
      'released v3 core pin is retained')
check(assignment(manager_source, 'V3_COMMIT_SHA') ==
      'c67d3f0ebb9fdcde61ea508b933a56d0c5da3b95695cd9fc19f70310bdfb211f',
      'released v3 commit pin is retained')
check(assignment(manager_source, 'V3_SERVICE_SHA') ==
      '8aed10eae6decece2abbd013a15c58ccf5b3ff5033c3c4bfa335d8e01648cbb7',
      'released v3 service pin is retained')
check(core_pin == sha((project / 'standalone/runtime-core.js').read_bytes()),
      'public core pin matches generated core')
check(commit_pin == sha((project / 'standalone/runtime-commit.js').read_bytes()),
      'runtime commit pin matches generated commit')
check(service_pin == sha((project / 'standalone/runtime-service.sh').read_bytes()),
      'service pin matches the byte-frozen supervisor')
check(runtime_pin == assignment(manager_source, 'CODE24_RUNTIME_SHA'),
      'RUNTIME_SHA remains the recognized v2 baseline')
check('runtime-hook.js' not in manager_source,
      'v4 manager never falls back to the old composite runtime-hook.js')
check('"$RUNTIME_SHA:$SERVICE_SHA"' not in manager_source,
      'new service is never accepted with the old runtime hook')
for feature in FEATURES:
    path = project / 'plugins/launcher' / feature / 'hook.js'
    check(path.is_file() and path.stat().st_size > 0, f'launcher package exists: {feature}')
check((project / 'plugins/launcher/monet/theme.jar').read_bytes()[0:2] == b'PK',
      'generated monet package carries a JAR')
check(not PLUGIN['monet'].endswith(b'\n'),
      'fixture includes a hook package without a trailing newline')


class Harness:
    def __init__(self, parent, name, base_ready=False):
        self.root = parent / name
        self.root.mkdir()
        self.base = self.root / 'base'
        self.source = self.root / 'source'
        self.widget_source = self.root / 'widget-source'
        self.watchhide = self.root / 'watchhide'
        self.system = self.root / 'system'
        self.settings = self.root / 'settings'
        self.shared = self.root / 'shared'
        self.legacy = self.root / 'legacy'
        self.bin = self.root / 'bin'
        self.proc = self.root / 'proc'
        for directory in (self.base, self.source, self.widget_source, self.watchhide,
                          self.system, self.settings, self.shared, self.legacy,
                          self.bin, self.proc):
            directory.mkdir(exist_ok=True)
        (self.base / 'modules').mkdir(exist_ok=True)
        self.pid = 424242
        self.pid_file = self.root / 'pid'
        self.pid_file.write_text(f'{self.pid}\n', encoding='utf-8', newline='\n')
        self.write_proc(self.pid, maps='')
        (self.root / 'shared-theme.jar').write_bytes(OLD_THEME)
        (self.root / 'system/sys_process_switch_list.xml').write_text(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<process_switch_list>\n'
            '  <allow_process>com.example.keep</allow_process>\n'
            f'  <allow_process>{APP}</allow_process>\n'
            '</process_switch_list>\n', encoding='utf-8', newline='\n')
        shutil.copyfile(project / 'widget-host/WatchHide-Launcher-Hook-v13.js',
                        self.widget_source / 'WatchHide-Launcher-Hook-v13.js')
        self.write_stubs()
        if base_ready:
            (self.base / 'frida-inject').write_bytes(FRIDA)
            (self.base / 'manager-ready').touch()
        self.set_setting('user.theme.mode', 'saved-mode')
        self.set_setting('user.custom.option', 'keep-this')
        self.set_setting('codex_qc_hide_breeno', '1')
        self.write_payloads()
        self.write_manager()
        self.write_wrappers()

    def write_proc(self, pid, maps=''):
        directory = self.proc / str(pid)
        directory.mkdir(parents=True, exist_ok=True)
        (directory / 'cmdline').write_bytes(PKG.encode() + b'\0' * 48)
        fields = ['123', f'({PKG})', 'S'] + ['0'] * 20
        fields[21] = str(1000 + int(pid))
        (directory / 'stat').write_text(' '.join(fields) + '\n', encoding='utf-8', newline='\n')
        (directory / 'maps').write_text(maps, encoding='utf-8', newline='\n')

    def set_pid(self, pid):
        self.pid = int(pid)
        self.pid_file.write_text(f'{self.pid}\n', encoding='utf-8', newline='\n')
        self.write_proc(self.pid)

    def current_pid(self):
        return int(self.pid_file.read_text(encoding='utf-8').strip())

    def write_cmd(self, name, body):
        path = self.bin / name
        path.write_text('#!/bin/sh\n' + body, encoding='utf-8', newline='\n')
        path.chmod(0o755)

    def write_stubs(self):
        self.write_cmd('getprop', '''case "$1" in
ro.product.model) echo "${SIM_MODEL:-OW20W1}";;
ro.build.display.id) echo OW20W1_11_A.92;;
ro.build.version.sdk) echo 27;;
ro.product.cpu.abi) echo armeabi-v7a;;
esac
''')
        self.write_cmd('id', 'echo "${SIM_UID:-0}"\n')
        self.write_cmd('pidof', 'cat "$SIM_ROOT/pid" 2>/dev/null || true\n')
        self.write_cmd('sleep', 'exit 0\n')
        self.write_cmd('chown', 'exit 0\n')
        self.write_cmd('setsid', 'exec "$@"\n')
        self.write_cmd('am', 'echo "am $*" >> "$SIM_ROOT/actions"\n')
        self.write_cmd('settings', '''mkdir -p "$SIM_ROOT/settings"
case "$1" in
  get) cat "$SIM_ROOT/settings/$3" 2>/dev/null || echo null;;
  put) printf "%s\\n" "$4" > "$SIM_ROOT/settings/$3";;
esac
''')

    def service_stub(self):
        return '''#!/bin/sh
BASE="$SIM_ROOT/base"
printf '%s\\n' "${1:-status}" >> "$SIM_ROOT/service-calls"
enabled() {
  case "$1" in
    monet|widgets|capture) [ -f "$BASE/modules/$1" ] && [ ! -f "$BASE/modules/$1.disabled" ];;
    *) [ -f "$BASE/modules/$1/installed" ] && [ ! -f "$BASE/modules/$1/disabled" ];;
  esac
}
write_extensions() {
  first=1
  printf '{'
  for path in "$BASE"/modules/*; do
    [ -d "$path" ] || continue
    id=${path##*/}
    [ -f "$path/installed" ] || continue
    [ -f "$BASE/payloads/$id/hook.js" ] || continue
    value=false; enabled "$id" && value=true
    [ "$first" = 1 ] || printf ','
    first=0
    printf '"%s":%s' "$id" "$value"
  done
  printf '}'
}
write_marker() {
  [ ! -f "$SIM_ROOT/no-marker" ] || return 0
  p=$(cat "$SIM_ROOT/pid" 2>/dev/null || echo 0)
  m=false; enabled monet && m=true
  w=false; enabled widgets && w=true
  c=false; enabled capture && c=true
  printf '{"version":2,"pid":%s,"error":"","monet":%s,"widgets":%s,"capture":%s,"extensions":' \
    "$p" "$m" "$w" "$c" > "$SIM_ROOT/marker.json"
  write_extensions >> "$SIM_ROOT/marker.json"
  printf '}\\n' >> "$SIM_ROOT/marker.json"
}
case "${1:-status}" in
  ensure)
    if [ -f "$BASE/modules/monet" ] && [ ! -f "$BASE/theme.jar" ]; then exit 1; fi
    write_marker; exit 0;;
  run) write_marker; exit 0;;
  stop) exit 0;;
  status) echo 'service_pid='; exit 0;;
  restart-launcher) echo 'fixture launcher restart'; exit 0;;
  *) echo 'Usage: ensure|status|stop|restart-launcher'; exit 2;;
esac
'''

    def write_payloads(self):
        (self.source / 'runtime-core.js').write_bytes(CORE)
        (self.source / 'runtime-commit.js').write_bytes(COMMIT)
        (self.source / 'runtime-service.sh').write_text(
            self.service_stub(), encoding='utf-8', newline='\n')
        for feature, body in PLUGIN.items():
            path = self.source / 'plugins' / feature
            path.mkdir(parents=True, exist_ok=True)
            (path / 'hook.js').write_bytes(body)
        (self.source / 'plugins/monet/theme.jar').write_bytes(THEME)

    def set_plugin(self, feature, body=None, theme=None):
        path = self.source / 'plugins' / feature
        path.mkdir(parents=True, exist_ok=True)
        if body is not None:
            (path / 'hook.js').write_bytes(body)
        if feature == 'monet' and theme is not None:
            (path / 'theme.jar').write_bytes(theme)

    def remove_plugins(self):
        shutil.rmtree(self.source / 'plugins')

    def write_manager(self, cp_failure=None):
        text = manager_source
        text = replace_assignment(text, 'CORE_SHA', sha(CORE))
        text = replace_assignment(text, 'COMMIT_SHA', sha(COMMIT))
        text = replace_assignment(
            text, 'SERVICE_SHA', sha((self.source / 'runtime-service.sh').read_bytes()))
        text = replace_assignment(text, 'OLD_THEME_SHA', sha(OLD_THEME))
        text = replace_assignment(text, 'OLD_HOOK_SHA', sha(OLD_HOOK))
        text = replace_assignment(text, 'OLD_SERVICE_SHA', sha(OLD_SERVICE))
        text = replace_assignment(text, 'RUNTIME_SHA', sha(V2_RUNTIME))
        text = replace_assignment(text, 'V3_CORE_SHA', sha(V3_CORE))
        text = replace_assignment(text, 'V3_COMMIT_SHA', sha(V3_COMMIT))
        text = replace_assignment(text, 'V3_SERVICE_SHA', sha(V3_SERVICE))
        text = replace_assignment(text, 'CODE24_RUNTIME_SHA', sha(CODE24_RUNTIME))
        text = replace_assignment(text, 'CODE24_SERVICE_SHA', sha(CODE24_SERVICE))
        text = replace_assignment(text, 'FRIDA_SHA', sha(FRIDA))
        text = text.replace('d0d3406f1ce940f20f2089b2962c41d5eb0830249cd781887f37fecc90c088d7', sha(KNOWN_SHARED))
        text = text.replace('BASE=/data/local/tmp/watchworkshop-runtime', 'BASE=./base')
        text = text.replace('SOURCE=/data/user/0/dev.codex.watchworkshop/files/monet', 'SOURCE=./source')
        text = text.replace('SHARED=/data/local/tmp/watchhide/hook.js', 'SHARED=./shared/hook.js')
        text = text.replace('LEGACY=/data/local/tmp/watch-monet/hook.js', 'LEGACY=./legacy/hook.js')
        text = text.replace('MARK=/data/user_de/0/com.heytap.wearable.launcher/files/watchworkshop-runtime.json',
                            'MARK=./marker.json')
        text = text.replace('/data/local/tmp/watchface-theme/theme.jar', './shared-theme.jar')
        text = text.replace('/proc/', './proc/')
        prefix = '''export PATH="$PWD/bin:$PATH"
export SIM_ROOT="$PWD"
kill() {
  echo "kill $*" >> "$SIM_ROOT/actions"
  if [ "${1:-}" = -9 ]; then
    old=$(cat "$SIM_ROOT/pid" 2>/dev/null || echo 0)
    next=$((old + 1))
    printf '%s\\n' "$next" > "$SIM_ROOT/pid"
    mkdir -p "$SIM_ROOT/proc/$next"
    printf '%s' com.heytap.wearable.launcher > "$SIM_ROOT/proc/$next/cmdline"
    printf '\\0\\0\\0\\0\\0\\0\\0\\0' >> "$SIM_ROOT/proc/$next/cmdline"
    printf '123 (%s) S 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 %s\\n' \
      com.heytap.wearable.launcher "$((1000 + next))" > "$SIM_ROOT/proc/$next/stat"
    : > "$SIM_ROOT/proc/$next/maps"
  fi
}
'''
        if cp_failure:
            prefix += f'''cp() {{
  case "$*" in *'{cp_failure}'*) echo 'injected cp failure' >&2; return 1;; esac
  command cp "$@"
}}
'''
        text = text.replace('#!/system/bin/sh\n', '#!/system/bin/sh\n' + prefix, 1)
        self.manager_path = self.source / 'runtime-manage.sh'
        self.manager_path.write_text(text, encoding='utf-8', newline='\n')

    def write_wrappers(self):
        widget = widget_source
        widget = widget.replace('BASE=/data/local/tmp/watchhide', 'BASE=./watchhide')
        widget = widget.replace('SOURCE=/data/user/0/dev.codex.watchworkshop/files/widget-host',
                                'SOURCE=./widget-source')
        widget = widget.replace('SYS=/data/system/sys_process_switch_list.xml',
                                'SYS=./system/sys_process_switch_list.xml')
        widget = widget.replace('/data/user/0/dev.codex.watchworkshop/files/monet/runtime-manage.sh',
                                './source/runtime-manage.sh')
        widget = widget.replace('BASE=/data/local/tmp/watchworkshop-runtime', 'BASE=./base')
        widget = widget.replace('/data/local/tmp/watchface-theme/theme.jar', './shared-theme.jar')
        widget = widget.replace('/proc/', './proc/')
        widget = widget.replace('#!/system/bin/sh\n',
                                '#!/system/bin/sh\nexport PATH="$PWD/bin:$PATH"\nexport SIM_ROOT="$PWD"\n', 1)
        self.widget_wrapper = self.root / 'widget-manage.sh'
        self.widget_wrapper.write_text(widget, encoding='utf-8', newline='\n')
        module = module_source.replace(
            '/data/user/0/dev.codex.watchworkshop/files/monet/runtime-manage.sh',
            './source/runtime-manage.sh')
        self.module_wrapper = self.root / 'module-manage.sh'
        self.module_wrapper.write_text(module, encoding='utf-8', newline='\n')

    def run(self, script, *script_args, ok=True, rc=0, env=None):
        run_env = os.environ.copy()
        run_env.update(env or {})
        process = subprocess.run(
            [args.bash, '--noprofile', '--norc', script, *map(str, script_args)],
            cwd=self.root, env=run_env, capture_output=True, text=True, timeout=30)
        expected = rc if ok else None
        if expected is not None:
            check(process.returncode == expected,
                  f'{script} {" ".join(map(str, script_args))}: '
                  f'rc={process.returncode} out={process.stdout!r} err={process.stderr!r}')
        else:
            check(process.returncode != 0,
                  f'{script} {" ".join(map(str, script_args))} unexpectedly passed')
        lines = process.stdout.strip().splitlines()
        return lines, process.stdout + process.stderr

    def manager(self, *manager_args, **kwargs):
        return self.run('./source/runtime-manage.sh', *manager_args, **kwargs)

    def service(self, *service_args, **kwargs):
        return self.run('./base/runtime-service.sh', *service_args, **kwargs)

    def widget(self, *widget_args, **kwargs):
        return self.run('./widget-manage.sh', *widget_args, **kwargs)

    def module(self, *module_args, **kwargs):
        return self.run('./module-manage.sh', *module_args, **kwargs)

    def status(self, feature):
        lines, _ = self.manager('status', feature)
        check(len(lines) == 1, f'status {feature} returned {lines}')
        return lines[0]

    def setting(self, key):
        try:
            return (self.settings / key).read_text(encoding='utf-8').strip()
        except FileNotFoundError:
            return None

    def set_setting(self, key, value):
        (self.settings / key).write_text(f'{value}\n', encoding='utf-8', newline='\n')

    def feature_enabled(self, feature):
        if feature in FEATURES:
            return ((self.base / 'modules' / feature).is_file()
                    and not (self.base / 'modules' / f'{feature}.disabled').exists())
        return ((self.base / 'modules' / feature / 'installed').is_file()
                and not (self.base / 'modules' / feature / 'disabled').exists())

    def installed_features(self):
        return tuple(f for f in FEATURES if (self.base / 'payloads' / f / 'hook.js').is_file())

    def third_features(self):
        return tuple(sorted(path.name for path in (self.base / 'payloads').glob('*')
                            if path.name not in FEATURES and (path / 'hook.js').is_file()))

    def installed_ids(self):
        return tuple(f for f in FEATURES if f in self.installed_features()) + self.third_features()

    def marker(self):
        return json.loads((self.root / 'marker.json').read_text(encoding='utf-8'))

    def write_marker(self, **overrides):
        try:
            extensions = json.loads(self.setting('owornate_launcher_plugins'))
        except (TypeError, ValueError, json.JSONDecodeError):
            extensions = {plugin_id: self.feature_enabled(plugin_id)
                          for plugin_id in self.third_features()}
        marker = {
            'version': 2,
            'pid': self.current_pid(),
            'error': '',
            'monet': self.feature_enabled('monet'),
            'widgets': self.feature_enabled('widgets'),
            'capture': self.feature_enabled('capture'),
            'extensions': extensions,
        }
        marker.update(overrides)
        (self.root / 'marker.json').write_text(
            json.dumps(marker, separators=(',', ':')) + '\n',
            encoding='utf-8', newline='\n')

    def combined(self):
        parts = [(self.base / 'runtime-core.js').read_bytes()]
        for feature in FEATURES:
            path = self.base / 'payloads' / feature / 'hook.js'
            if path.exists():
                parts.append(path.read_bytes())
        third = {plugin_id: (self.base / 'payloads' / plugin_id / 'hook.js').read_bytes()
                 for plugin_id in self.third_features()}
        parts.extend(wrapped_third(plugin_id, third[plugin_id]) for plugin_id in sorted(third))
        parts.append((self.base / 'runtime-commit.js').read_bytes())
        return b'\n;\n'.join(parts)

    def runtime_snapshot(self):
        result = {}
        paths = list(self.base.rglob('*'))
        marker = self.root / 'marker.json'
        if marker.exists():
            paths.append(marker)
        for path in sorted(paths):
            relative = path.relative_to(self.root).as_posix()
            if path.is_dir():
                result[relative + '/'] = None
            else:
                result[relative] = (path.read_bytes(), stat.S_IMODE(path.stat().st_mode))
        return result

    def start_count(self):
        path = self.root / 'service-calls'
        if not path.exists():
            return 0
        return sum(line in ('ensure', 'run') for line in path.read_text().splitlines())

    def starts_are_serialized(self):
        path = self.root / 'service-calls'
        if not path.exists():
            return True
        previous = None
        for line in path.read_text().splitlines():
            if line in ('ensure', 'run') and previous in ('ensure', 'run'):
                return False
            previous = line
        return True

    def install(self, feature):
        lines, _ = self.manager('install', feature)
        check(lines == ['INSTALLED'], f'install {feature}: {lines}')
        check((self.base / 'modules' / feature).exists(), f'install keeps {feature} record')
        check((self.base / 'modules' / f'{feature}.disabled').exists(), f'install starts {feature} disabled')
        check(self.setting(f'codex_{feature}_enabled') == '0', f'install projects {feature}=0')

    def enable(self, feature):
        lines, _ = self.manager('enable', feature)
        check(lines == ['ENABLED'], f'enable {feature}: {lines}')
        check(self.feature_enabled(feature), f'enable removes {feature}.disabled')
        check(self.setting(f'codex_{feature}_enabled') == '1', f'enable projects {feature}=1')

    def disable(self, feature):
        lines, _ = self.manager('disable', feature)
        check(lines == ['DISABLED_OR_REMOVED'], f'disable {feature}: {lines}')
        check(not self.feature_enabled(feature), f'disable records {feature} off')
        check(self.setting(f'codex_{feature}_enabled') == '0', f'disable projects {feature}=0')

    def uninstall(self, feature):
        lines, _ = self.manager('uninstall', feature)
        check(lines == ['DISABLED_OR_REMOVED'], f'uninstall {feature}: {lines}')
        check(feature not in self.installed_features(), f'uninstall removes {feature} payload')
        check(not (self.base / 'modules' / feature).exists(), f'uninstall removes {feature} record')
        check(self.setting(f'codex_{feature}_enabled') == '0', f'uninstall projects {feature}=0')

    def make_v2(self, modules=FEATURES, disabled=(), legacy=False):
        if legacy:
            (self.base / 'frida-inject').write_bytes(FRIDA)
            (self.base / 'hook.js').write_bytes(OLD_HOOK)
            (self.base / 'theme.jar').write_bytes(OLD_THEME)
            (self.base / 'monet-service.sh').write_bytes(OLD_SERVICE)
            (self.base / 'installed').write_text('old\n', encoding='utf-8', newline='\n')
            if 'monet' in disabled:
                (self.base / 'disabled').touch()
        else:
            (self.base / 'runtime-version').write_text('2\n', encoding='utf-8', newline='\n')
            (self.base / 'hook.js').write_bytes(CODE24_RUNTIME)
            (self.base / 'runtime-service.sh').write_bytes(CODE24_SERVICE)
            (self.base / 'theme.jar').write_bytes(OLD_THEME)
        for feature in modules:
            (self.base / 'modules' / feature).touch()
            if feature in disabled:
                (self.base / 'modules' / f'{feature}.disabled').touch()

    def make_v3(self, modules=FEATURES, disabled=()):
        shutil.rmtree(self.base / 'payloads', ignore_errors=True)
        for path in (self.base / 'modules').glob('*'):
            path.unlink()
        (self.base / 'runtime-version').write_text('3\n', encoding='utf-8', newline='\n')
        (self.base / 'runtime-core.js').write_bytes(V3_CORE)
        (self.base / 'runtime-commit.js').write_bytes(V3_COMMIT)
        (self.base / 'runtime-service.sh').write_bytes(V3_SERVICE)
        payloads = []
        for feature in FEATURES:
            if feature not in modules:
                continue
            directory = self.base / 'payloads' / feature
            directory.mkdir(parents=True)
            (directory / 'hook.js').write_bytes(PLUGIN[feature])
            (directory / 'hook.sha256').write_text(sha(PLUGIN[feature]) + '\n',
                                                   encoding='utf-8', newline='\n')
            payloads.append(PLUGIN[feature])
            (self.base / 'modules' / feature).touch()
            if feature in disabled:
                (self.base / 'modules' / f'{feature}.disabled').touch()
        if 'monet' in modules:
            (self.base / 'theme.jar').write_bytes(THEME)
            (self.base / 'payloads/monet/theme.sha256').write_text(
                sha(THEME) + '\n', encoding='utf-8', newline='\n')
        hook = combined(V3_CORE, payloads, V3_COMMIT)
        (self.base / 'hook.js').write_bytes(hook)
        (self.base / 'hook.sha256').write_text(sha(hook) + '\n', encoding='utf-8', newline='\n')


with tempfile.TemporaryDirectory(prefix='runtime-lifecycle-', dir=work) as scratch:
    parent = Path(scratch)
    assert parent.resolve().parent == work.resolve()

    def harness(name, base_ready=True):
        return Harness(parent, name, base_ready=base_ready)

    def install_third(case, plugin_id, body):
        case.set_plugin(plugin_id, body)
        lines, _ = case.manager('install', plugin_id)
        check(lines == ['INSTALLED'], f'install third-party {plugin_id}: {lines}')
        check((case.base / 'payloads' / plugin_id / 'hook.js').read_bytes() == body,
              f'{plugin_id} payload stored')
        check((case.base / 'modules' / plugin_id / 'installed').is_file(),
              f'{plugin_id} uses installed state marker')
        check((case.base / 'modules' / plugin_id / 'disabled').is_file(),
              f'{plugin_id} installs disabled')
        check(not (case.base / 'modules' / f'{plugin_id}.disabled').exists(),
              f'{plugin_id} does not use the official sidecar layout')
        mask = json.loads(case.setting('owornate_launcher_plugins'))
        check(mask[plugin_id] is False, f'{plugin_id} projected disabled in JSON settings')

    def write_real_service(case):
        source = (project / 'standalone/runtime-service.sh').read_text(encoding='utf-8')
        source = source.replace('BASE=/data/local/tmp/watchworkshop-runtime', 'BASE=./base')
        source = source.replace('MARK=/data/user_de/0/$PKG/files/watchworkshop-runtime.json',
                                'MARK=./marker.json')
        source = source.replace('/data/local/tmp/watchhide/hook.js', './shared/hook.js')
        source = source.replace('/data/local/tmp/watch-monet/hook.js', './legacy/hook.js')
        source = source.replace('/proc/', './proc/')
        source = source.replace('[ -x "$INJECT" ] && [ -r "$BASE/hook.js" ]',
                                '[ -r "$INJECT" ] && [ -r "$BASE/hook.js" ]')
        source = source.replace('#!/system/bin/sh\n',
                                '#!/system/bin/sh\nexport PATH="$PWD/bin:$PATH"\nexport SIM_ROOT="$PWD"\n', 1)
        (case.root / 'real-service.sh').write_text(source, encoding='utf-8', newline='\n')

    def run_real_service_guard(name, monet_installed, third_only=False):
        case = harness(name)
        case.write_cmd('setsid', 'exit 0\n')
        (case.base / 'hook.js').write_bytes(b'fixture hook\n')
        (case.base / 'runtime-version').write_text('4\n', encoding='utf-8', newline='\n')
        (case.base / 'frida-inject').write_bytes(FRIDA)
        (case.base / 'frida-inject').chmod(0o755)
        installed = 'com.example.alpha' if third_only else 'widgets'
        if third_only:
            (case.base / 'modules' / installed).mkdir()
            (case.base / 'modules' / installed / 'installed').touch()
        else:
            (case.base / 'modules' / installed).touch()
        (case.base / 'payloads' / installed).mkdir(parents=True)
        (case.base / 'payloads' / installed / 'hook.js').write_bytes(b'fixture payload\n')
        (case.base / 'payloads' / installed / 'hook.sha256').write_text(
            sha(b'fixture payload\n') + '\n', encoding='utf-8', newline='\n')
        if monet_installed:
            (case.base / 'modules/monet').touch()
            (case.base / 'modules/monet.disabled').touch()
            (case.base / 'payloads/monet').mkdir(parents=True)
            (case.base / 'payloads/monet/hook.js').write_bytes(b'fixture monet\n')
        write_real_service(case)
        lines, output = case.run('./real-service.sh', 'ensure', ok=False)
        return output

    if args.section in ('all', 'guards'):
        guard_output = run_real_service_guard('real-service-theme-free', monet_installed=False)
        check('Runtime files missing' not in guard_output and 'Service startup failed' in guard_output,
              f'real service ensure passes dependency guard without monet/theme: {guard_output!r}')
        guard_output = run_real_service_guard('real-service-monet-no-theme', monet_installed=True)
        check('Runtime files missing' in guard_output,
              f'real service ensure rejects installed monet without theme.jar: {guard_output!r}')
        guard_output = run_real_service_guard(
            'real-service-third-only', monet_installed=False, third_only=True)
        check('All features disabled' not in guard_output and 'Service startup failed' in guard_output,
              f'real service ensure sees third-party-only modules: {guard_output!r}')

        theme_transition = harness('theme-transition')
        theme_transition.install('monet')
        theme_transition.install('widgets')
        theme_transition.enable('widgets')
        check((theme_transition.base / 'theme.jar').exists(), 'monet install publishes its theme')
        theme_transition.uninstall('monet')
        check(not (theme_transition.base / 'theme.jar').exists(),
              'removing monet clears the theme from the remaining widgets runtime')
        check(theme_transition.status('widgets') == 'ready',
              'widgets restart through ensure after monet/theme removal')
        check(not list(theme_transition.base.glob('.runtime-stage.*')),
              'successful stage switch removes the empty staging directory')

    # Fresh setup is read-only; a prepared base stays read-only until a package is
    # intentionally imported.
    fresh = harness('fresh', base_ready=False)
    before = fresh.runtime_snapshot()
    lines, _ = fresh.manager('status', 'monet')
    check(lines == ['setup'], f'fresh status: {lines}')
    check(fresh.runtime_snapshot() == before, 'fresh status is read-only')
    check(not (fresh.base / 'modules/monet').exists() and not (fresh.root / 'marker.json').exists(),
          'fresh status creates no module or marker')

    ready = harness('ready')
    before = ready.runtime_snapshot()
    for feature in FEATURES:
        check(ready.status(feature) == 'available', f'ready base reports {feature}=available')
    lines, _ = ready.manager('kind')
    check(lines == ['own'], f'ready base kind: {lines}')
    check(ready.runtime_snapshot() == before, 'prepared available/status path is read-only')

    # Six install/enable orders. Each install adds only its source package and
    # starts disabled; the combination is rebuilt in monet/widgets/capture order.
    if not args.skip_orders and args.section in ('all', 'orders'):
        for index, order in enumerate(itertools.permutations(FEATURES), 1):
            case = harness(f'order-{index}')
            completed = []
            for feature in order:
                case.install(feature)
                check(case.installed_features() == tuple(f for f in FEATURES if f in completed + [feature]),
                      f'order {order}: only imported payloads stored after {feature}')
                check((case.base / 'runtime-version').read_text().strip() == '4',
                      f'order {order}: first import publishes v4')
                case.enable(feature)
                completed.append(feature)
                current_state = 'installed' if feature == 'monet' else 'ready'
                check(case.status(feature) == current_state,
                      f'order {order}: {feature} enabled as {current_state}')
                marker = case.marker()
                check(marker['version'] == 2 and marker['pid'] == case.current_pid() and marker['error'] == '',
                      f'order {order} marker identity after {feature}')
                check([marker[f] for f in FEATURES] == [f in completed for f in FEATURES],
                      f'order {order} marker mask after {feature}')
                check((case.base / 'theme.jar').exists() == ('monet' in completed),
                      f'order {order}: monet theme compatibility file is conditional')
                expected = [PLUGIN[f] for f in FEATURES if f in completed]
                check(case.combined() == combined(CORE, expected, COMMIT),
                      f'order {order}: combined hook order after {feature}')
                check((case.base / 'hook.js').read_bytes() == case.combined(),
                      f'order {order}: published hook matches staged combination')
                check(b'\n;\n' in (case.base / 'hook.js').read_bytes() and
                      b'\n;\n'.join([CORE, *expected, COMMIT]) == case.combined(),
                      f'order {order}: fixed separator protects adjacent JS files')
                check((case.base / 'hook.sha256').read_text().strip() == sha(case.combined()),
                      f'order {order}: combined receipt matches')
                check(not list(case.base.glob('.runtime-stage.*')),
                      f'order {order}: successful switch leaves no empty stage directory')
                check(case.start_count() >= 1 and case.starts_are_serialized(),
                      f'order {order}: supervisor starts are serialized, never doubled')
            check(all(case.feature_enabled(f) for f in FEATURES), f'order {order} all enabled')
            for feature in FEATURES:
                expected = 'installed' if feature == 'monet' else 'ready'
                check(case.status(feature) == expected, f'order {order} final {feature}')

    if args.section in ('all', 'lifecycle'):
        # Disable/uninstall/edit one role without disturbing the other packages,
        # switches, settings, or saved manager data.
        isolation = harness('isolation')
        for feature in FEATURES:
            isolation.install(feature)
            isolation.enable(feature)
        saved = isolation.base / 'install-state.json'
        saved.write_text('{"theme":"user-saved"}\n', encoding='utf-8', newline='\n')
        saved_bytes = saved.read_bytes()
        hidden_before = isolation.setting('codex_qc_hide_breeno')
        custom_before = isolation.setting('user.custom.option')

        isolation.disable('widgets')
        check(isolation.feature_enabled('monet') and not isolation.feature_enabled('widgets')
              and isolation.feature_enabled('capture'), 'disable widgets leaves other features enabled')
        check(isolation.setting('codex_widgets_enabled') == '0'
              and isolation.setting('codex_monet_enabled') == '1'
              and isolation.setting('codex_capture_enabled') == '1', 'disable updates only widget setting')
        check((isolation.base / 'hook.js').exists() and (isolation.base / 'theme.jar').exists()
              and (isolation.base / 'runtime-service.sh').exists(), 'disable preserves runtime files')
        check(saved.read_bytes() == saved_bytes, 'disable preserves saved user configuration')
        check(isolation.setting('codex_qc_hide_breeno') == hidden_before
              and isolation.setting('user.custom.option') == custom_before,
              'disable preserves unrelated and Breeno settings')
        check(isolation.marker()['widgets'] is False and isolation.marker()['monet'] is True,
              'disable refreshes marker mask')

        keep_before = {
            name: (isolation.base / 'payloads' / name / 'hook.js').read_bytes()
            for name in ('monet', 'widgets')
        }
        receipt_before = {
            name: (isolation.base / 'payloads' / name / 'hook.sha256').read_bytes()
            for name in ('monet', 'widgets')
        }
        isolation.uninstall('capture')
        check(isolation.installed_features() == ('monet', 'widgets'),
              'capture uninstall removes only capture payload')
        check(all((isolation.base / 'payloads' / name / 'hook.js').read_bytes() == keep_before[name]
                  and (isolation.base / 'payloads' / name / 'hook.sha256').read_bytes() == receipt_before[name]
                  for name in keep_before), 'capture uninstall preserves other hooks and receipts')
        check(isolation.feature_enabled('monet') and not isolation.feature_enabled('widgets'),
              'capture removal leaves prior enabled choices')
        check(isolation.setting('codex_capture_enabled') == '0', 'capture removal projects capture=0')
        check(saved.read_bytes() == saved_bytes, 'uninstall preserves saved user configuration')
        check(isolation.combined() == combined(CORE, [PLUGIN['monet'], PLUGIN['widgets']], COMMIT),
              'capture removal rebuilds the remaining combination')

        # Once a v4 role is deployed, stop/enable/disable/uninstall must not
        # depend on SOURCE/plugins for that role. Keep only the public core in SOURCE.
        isolation.remove_plugins()
        isolation.disable('monet')
        isolation.enable('monet')
        isolation.disable('widgets')
        isolation.enable('widgets')
        isolation.uninstall('widgets')
        check(isolation.installed_features() == ('monet',),
              'v4 uninstall works from deployed payload while source package is absent')
        check(isolation.status('monet') == 'installed', 'surviving v4 monet remains usable')
        isolation.uninstall('monet')
        check(isolation.installed_features() == (), 'last no-source uninstall removes all payloads')
        check(all(not (isolation.base / name).exists() for name in
                  ('hook.js', 'theme.jar', 'runtime-core.js', 'runtime-commit.js',
                   'runtime-service.sh', 'runtime-version', 'hook.sha256', 'payloads')),
              'last removal deletes runtime payloads only')
        check((isolation.base / 'manager-ready').exists()
              and (isolation.base / 'frida-inject').read_bytes() == FRIDA,
              'last removal retains base prerequisite environment')
        check(saved.read_bytes() == saved_bytes and isolation.setting('user.custom.option') == custom_before,
              'last removal retains user data and settings')
        check(all(isolation.setting(f'codex_{feature}_enabled') == '0' for feature in FEATURES),
              'last removal projects all feature settings off')
        for feature in FEATURES:
            check(isolation.status(feature) == 'available', f'{feature} available after last removal')

        # A disabled v4 role can be uninstalled directly.
        disabled_remove = harness('disabled-remove')
        disabled_remove.install('widgets')
        disabled_remove.uninstall('widgets')
        check(disabled_remove.installed_features() == (), 'disabled role can be uninstalled')
        check(disabled_remove.status('widgets') == 'available', 'disabled uninstall returns available')

        # A theme-free v4 deployment starts through the unchanged supervisor's run
        # entrypoint; its deployed payloads also remain operable with no SOURCE plugins.
        theme_free = harness('theme-free')
        theme_free.install('widgets')
        theme_free.enable('widgets')
        check(not (theme_free.base / 'theme.jar').exists(), 'widgets-only runtime has no theme JAR')
        theme_free.install('capture')
        theme_free.enable('capture')
        check(theme_free.marker()['widgets'] is True and theme_free.marker()['capture'] is True,
              'widgets/capture share one theme-free supervisor')
        theme_free.remove_plugins()
        theme_free.disable('widgets')
        theme_free.uninstall('capture')
        check(theme_free.installed_features() == ('widgets',), 'theme-free uninstall preserves another payload')
        theme_free.uninstall('widgets')
        check(theme_free.installed_features() == (), 'theme-free last payload clears cleanly')


        # Marker identity/mask remains the supervisor readiness contract.
        isolation.write_marker()
        for label, override in [
            ('version', {'version': 1}),
            ('pid', {'pid': isolation.current_pid() + 1}),
            ('mask', {'capture': True}),
            ('error', {'error': 'capture initialization failed'}),
        ]:
            isolation.write_marker(**override)
            before = isolation.runtime_snapshot()
            check(isolation.status('monet') == 'available', f'empty runtime is available despite marker {label}')
            check(isolation.runtime_snapshot() == before, f'marker {label} observation is read-only')

        marker_ready = harness('marker-ready')
        marker_ready.install('widgets')
        marker_ready.enable('widgets')
        for label, override in [
            ('version', {'version': 1}),
            ('pid', {'pid': marker_ready.current_pid() + 1}),
            ('mask', {'monet': True}),
            ('error', {'error': 'widgets initialization failed'}),
        ]:
            marker_ready.write_marker(**override)
            before = marker_ready.runtime_snapshot()
            check(marker_ready.status('widgets') == 'installed', f'marker {label} is not ready')
            check(marker_ready.runtime_snapshot() == before, f'marker {label} status is read-only')
        (marker_ready.root / 'no-marker').touch()
        (marker_ready.root / 'marker.json').unlink()
        _, output = marker_ready.manager('enable', 'widgets', ok=False)
        check('START_UNCONFIRMED' in output, f'unconfirmed startup reports error: {output!r}')
        check(marker_ready.status('widgets') == 'installed', 'unconfirmed startup is not falsely ready')
        (marker_ready.root / 'no-marker').unlink()
        marker_ready.enable('widgets')
        check(marker_ready.status('widgets') == 'ready', 'retry becomes ready only after marker')

        original_service = (marker_ready.base / 'runtime-service.sh').read_bytes()
        (marker_ready.base / 'runtime-service.sh').write_bytes(b'wrong service bytes\n')
        check(marker_ready.status('widgets') == 'conflict',
              'service hash mismatch is refused as unknown ownership')
        before = marker_ready.runtime_snapshot()
        _, output = marker_ready.manager('enable', 'widgets', ok=False)
        check('UNKNOWN_FILES' in output and marker_ready.runtime_snapshot() == before,
              'service hash mismatch cannot mutate')
        (marker_ready.base / 'runtime-service.sh').write_bytes(original_service)
        (marker_ready.base / 'runtime-version').write_text('1\n', encoding='utf-8', newline='\n')
        check(marker_ready.status('widgets') == 'conflict', 'unknown runtime version is refused')

        # Updating one role replaces only that payload. Other package bytes and
        # receipts survive, and SOURCE needs only the selected plugin.
        update = harness('update-one')
        for feature in FEATURES:
            update.install(feature)
            update.enable(feature)
        new_widgets = b'// replacement widgets plugin\nglobalThis.widgets = 2;\n'
        update.set_plugin('widgets', new_widgets)
        untouched = {
            feature: {
                'hook': (update.base / 'payloads' / feature / 'hook.js').read_bytes(),
                'receipt': (update.base / 'payloads' / feature / 'hook.sha256').read_bytes(),
            }
            for feature in ('monet', 'capture')
        }
        theme_before = (update.base / 'theme.jar').read_bytes()
        choices = {feature: update.feature_enabled(feature) for feature in FEATURES}
        lines, _ = update.manager('update', 'widgets')
        check(lines == ['UPDATED'], f'widgets update output: {lines}')
        check((update.base / 'payloads/widgets/hook.js').read_bytes() == new_widgets,
              'selected widgets payload was replaced')
        check(all((update.base / 'payloads' / feature / 'hook.js').read_bytes() == saved['hook']
                  and (update.base / 'payloads' / feature / 'hook.sha256').read_bytes() == saved['receipt']
                  for feature, saved in untouched.items()), 'widgets update preserves other payloads')
        check((update.base / 'theme.jar').read_bytes() == theme_before,
              'widgets update preserves monet theme')
        check({feature: update.feature_enabled(feature) for feature in FEATURES} == choices,
              'widgets update preserves every enabled choice')
        check(update.combined() == combined(CORE, [PLUGIN['monet'], new_widgets, PLUGIN['capture']], COMMIT),
              'widgets update publishes the ordered replacement combination')

        # Capture can be repaired when only its source package exists; installed
        # monet/widgets remain available from BASE.
        capture2 = b'// replacement capture plugin\nglobalThis.capture = 2;\n'
        update.remove_plugins()
        update.set_plugin('capture', capture2)
        lines, _ = update.manager('repair', 'capture')
        check(lines == ['UPDATED'], f'capture repair output: {lines}')
        check((update.base / 'payloads/capture/hook.js').read_bytes() == capture2,
              'capture repair replaces only capture')
        check(update.combined() == combined(CORE, [PLUGIN['monet'], new_widgets, capture2], COMMIT),
              'capture repair rebuilds from stored other payloads')

        missing_fresh = harness('missing-fresh')
        missing_fresh.set_plugin('widgets')
        (missing_fresh.source / 'plugins/widgets/hook.js').unlink()
        before = missing_fresh.runtime_snapshot()
        _, output = missing_fresh.manager('install', 'widgets', ok=False)
        check('PLUGIN_REQUIRED:widgets' in output and missing_fresh.runtime_snapshot() == before,
              'missing import package fails clearly and changes nothing')
        (missing_fresh.source / 'plugins/widgets/hook.js').write_bytes(b'')
        _, output = missing_fresh.manager('install', 'widgets', ok=False)
        check('PLUGIN_INVALID:widgets' in output and missing_fresh.runtime_snapshot() == before,
              'empty import package is rejected without mutation')
        (missing_fresh.source / 'plugins/widgets/hook.js').write_bytes(PLUGIN['widgets'])
        (missing_fresh.source / 'plugins/monet/theme.jar').write_bytes(b'not-a-jar\n')
        _, output = missing_fresh.manager('install', 'monet', ok=False)
        check('PLUGIN_INVALID:monet' in output and missing_fresh.runtime_snapshot() == before,
              'invalid JAR is rejected without publishing monot')
        (missing_fresh.source / 'plugins/monet/theme.jar').write_bytes(THEME)
        (missing_fresh.source / 'runtime-core.js').write_bytes(b'corrupt public core\n')
        _, output = missing_fresh.manager('install', 'widgets', ok=False)
        check('PAYLOAD_INVALID' in output and missing_fresh.runtime_snapshot() == before,
              'bad public core uses PAYLOAD_INVALID without mutation')
        (missing_fresh.source / 'runtime-core.js').write_bytes(CORE)

        retry = harness('retry')
        retry.write_manager(cp_failure='plugins/widgets/hook.js')
        before = retry.runtime_snapshot()
        _, output = retry.manager('install', 'widgets', ok=False)
        check('COPY_FAILED' in output and retry.runtime_snapshot() == before,
              'staging copy failure changes no target or marker')
        check(not any((retry.base / name).exists() for name in
                      ('hook.js', 'runtime-version', 'payloads')),
              'staging copy failure leaves no partial runtime')
        retry.write_manager()
        retry.install('widgets')
        check(retry.installed_features() == ('widgets',), 'retry after staging failure succeeds')

        # Fixed-name receipts and fixed core/service pins retain the ownership
        # boundary now that feature payloads are dynamic.
        receipt = harness('receipts')
        receipt.install('widgets')
        receipt.enable('widgets')
        original_payload = (receipt.base / 'payloads/widgets/hook.js').read_bytes()
        (receipt.base / 'modules/widgets').unlink()
        check(receipt.status('widgets') == 'conflict',
              'deployed payload without its module switch is conflict')
        (receipt.base / 'modules/widgets').touch()
        check(receipt.status('widgets') == 'ready', 'restored module switch rejoins ownership')
        (receipt.base / 'payloads/widgets/hook.js').write_bytes(original_payload + b'//tamper\n')
        check(receipt.status('widgets') == 'conflict', 'payload/receipt mismatch is conflict')
        before = receipt.runtime_snapshot()
        _, output = receipt.manager('update', 'widgets', ok=False)
        check('UNKNOWN_FILES' in output and receipt.runtime_snapshot() == before,
              'tampered payload receipt blocks update without mutation')
        (receipt.base / 'payloads/widgets/hook.js').write_bytes(original_payload)
        original_hook_receipt = (receipt.base / 'hook.sha256').read_bytes()
        (receipt.base / 'hook.sha256').write_text('0' * 64 + '\n', encoding='utf-8', newline='\n')
        check(receipt.status('widgets') == 'conflict', 'combination receipt mismatch is conflict')
        (receipt.base / 'hook.sha256').write_bytes(original_hook_receipt)
        original_core = (receipt.base / 'runtime-core.js').read_bytes()
        (receipt.base / 'runtime-core.js').write_bytes(original_core + b'//tamper\n')
        check(receipt.status('widgets') == 'conflict', 'core pin mismatch is conflict')
        (receipt.base / 'runtime-core.js').write_bytes(original_core)
        (receipt.base / 'payloads/foreign').mkdir()
        check(receipt.status('widgets') == 'conflict', 'unknown payload directory is conflict')

        # Released v2 and known code24 tuples remain readable and migrate only
        # when all previously installed feature packages are available.
        for updater in FEATURES:
            for all_disabled in (False, True):
                previous = harness(f'v2-{updater}-{all_disabled}')
                disabled = set(FEATURES if all_disabled else ('widgets',))
                previous.make_v2(disabled=disabled)
                saved = b'{"user":"preserved"}\n'
                (previous.base / 'install-state.json').write_bytes(saved)
                choices = {feature: previous.feature_enabled(feature) for feature in FEATURES}
                for feature in FEATURES:
                    check(previous.status(feature) == 'update', 'released v2 is recognized before migration')
                lines, _ = previous.manager('kind')
                check(lines == ['own'], 'released v2 remains owned while marked version 2')
                lines, _ = previous.manager('update', updater)
                check(lines == ['UPDATED'], f'{updater} v2 migration output: {lines}')
                check((previous.base / 'runtime-version').read_text().strip() == '4',
                      'migration publishes runtime-version=4')
                check(previous.installed_features() == FEATURES,
                      'migration imports every previously installed feature')
                for feature, expected in choices.items():
                    check(previous.feature_enabled(feature) == expected, 'v2 migration preserves enabled choices')
                    expected_status = 'disabled' if not expected else 'installed' if feature == 'monet' else 'ready'
                    check(previous.status(feature) == expected_status, 'v2 migration confirms resulting state')
                check((previous.base / 'install-state.json').read_bytes() == saved,
                      'v2 migration retains saved user options')
                check(previous.setting('user.custom.option') == 'keep-this',
                      'v2 migration retains unrelated settings')

        mixed = harness('mixed-v2')
        (mixed.base / 'runtime-version').write_text('2\n', encoding='utf-8', newline='\n')
        (mixed.base / 'theme.jar').write_bytes(OLD_THEME)
        (mixed.base / 'modules/widgets').touch()
        for hook, service in [(CODE24_RUNTIME, (mixed.source / 'runtime-service.sh').read_bytes()),
                              (V2_RUNTIME, (mixed.source / 'runtime-service.sh').read_bytes()),
                              (CODE24_RUNTIME, b'unknown service\n')]:
            (mixed.base / 'hook.js').write_bytes(hook)
            (mixed.base / 'runtime-service.sh').write_bytes(service)
            check(mixed.status('widgets') == 'conflict', 'unrecognized or mixed v2 tuple is not owned')
            before = mixed.runtime_snapshot()
            _, output = mixed.manager('update', 'widgets', ok=False)
            check('UNKNOWN_FILES' in output and mixed.runtime_snapshot() == before,
                  'mixed v2 tuple cannot be migrated')

        for label, disabled in (('archived-enabled', False), ('archived-disabled', True)):
            archived = harness(label, base_ready=False)
            archived.make_v2(modules=(), disabled=('monet',) if disabled else (), legacy=True)
            (archived.base / 'install-state.json').write_text('{"old":"saved"}\n',
                                                              encoding='utf-8', newline='\n')
            old_config = (archived.base / 'install-state.json').read_bytes()
            archived.set_setting('codex_monet_enabled', '0' if disabled else '1')
            check(archived.status('monet') == 'update', f'{label}: old baseline requires migration')
            lines, _ = archived.manager('install', 'monet')
            check(lines == ['INSTALLED'], f'{label}: migration output {lines}')
            check(archived.installed_features() == ('monet',), f'{label}: only monet payload migrated')
            check(archived.feature_enabled('monet') is not disabled, f'{label}: enabled intent preserved')
            check(archived.setting('codex_monet_enabled') == ('0' if disabled else '1'),
                  f'{label}: monet setting preserved')
            check(archived.status('monet') == ('disabled' if disabled else 'installed'),
                  f'{label}: migrated monet status')
            check((archived.base / 'install-state.json').read_bytes() == old_config,
                  f'{label}: saved user options preserved')
            check(not (archived.base / 'installed').exists()
                  and not (archived.base / 'monet-service.sh').exists()
                  and not (archived.base / 'disabled').exists(), f'{label}: old records retired')

        # A missing package during legacy migration is fatal even when the caller
        # selected another role; no existing feature is silently dropped.
        incomplete = harness('incomplete-v2')
        incomplete.make_v2()
        (incomplete.source / 'plugins/capture').rename(incomplete.root / 'capture-saved')
        before = incomplete.runtime_snapshot()
        _, output = incomplete.manager('update', 'widgets', ok=False)
        check('PLUGIN_REQUIRED:capture' in output and incomplete.runtime_snapshot() == before,
              'legacy migration requires every previously installed package')
        (incomplete.root / 'capture-saved').rename(incomplete.source / 'plugins/capture')
        (incomplete.source / 'plugins/widgets/hook.js').write_bytes(b'')
        _, output = incomplete.manager('update', 'widgets', ok=False)
        check('PLUGIN_INVALID:widgets' in output and incomplete.runtime_snapshot() == before,
              'invalid legacy migration package is rejected without mutation')

        widgets_only_v2 = harness('widgets-only-v2')
        widgets_only_v2.make_v2(modules=('widgets',), disabled=('widgets',))
        (widgets_only_v2.source / 'plugins/monet').rename(widgets_only_v2.root / 'monet-saved')
        lines, _ = widgets_only_v2.manager('update', 'widgets')
        check(lines == ['UPDATED'], f'widgets-only v2 migration output: {lines}')
        check(widgets_only_v2.installed_features() == ('widgets',),
              'widgets-only v2 migration does not invent monet')
        check(not (widgets_only_v2.base / 'theme.jar').exists(),
              'widgets-only v2 migration does not require or retain monet theme')
        check(widgets_only_v2.status('widgets') == 'disabled',
              'widgets-only v2 migration preserves disabled state')

        # Unknown/foreign ownership remains blocked before any v4 mutation.
        conflict = harness('conflict')
        conflict.shared.joinpath('hook.js').write_bytes(b'unknown shared hook\n')
        before = conflict.runtime_snapshot()
        check(conflict.status('monet') == 'conflict', 'unknown shared hook reports conflict')
        _, output = conflict.manager('install', 'widgets', ok=False)
        check('UNKNOWN_FILES' in output and conflict.runtime_snapshot() == before,
              'unknown shared hook refusal preserves all runtime files')
        (conflict.shared / 'hook.js').unlink()
        conflict.legacy.joinpath('hook.js').write_bytes(b'foreign legacy hook\n')
        check(conflict.status('monet') == 'conflict', 'legacy hook reports conflict')
        before = conflict.runtime_snapshot()
        _, output = conflict.manager('enable', 'monet', ok=False)
        check('UNKNOWN_FILES' in output and conflict.runtime_snapshot() == before,
              'legacy ownership refusal preserves all files')
        (conflict.legacy / 'hook.js').unlink()
        (conflict.base / 'hook.js').write_bytes(b'foreign base payload\n')
        check(conflict.status('widgets') == 'conflict', 'unknown base payload reports conflict')
        before = conflict.runtime_snapshot()
        _, output = conflict.manager('install', 'widgets', ok=False)
        check('UNKNOWN_FILES' in output and conflict.runtime_snapshot() == before,
              'unknown base payload refusal is mutation-free')
        (conflict.base / 'hook.js').unlink()

        conflict.set_pid(conflict.pid + 10)
        conflict.write_proc(conflict.pid,
                            maps='7f00-7f01 r-xp 00000000 fe:00 1 /data/local/tmp/frida-agent-32.so\n')
        before = conflict.runtime_snapshot()
        _, output = conflict.manager('install', 'capture', ok=False)
        check('FOREIGN_RUNTIME' in output and conflict.runtime_snapshot() == before,
              'foreign running agent refusal is mutation-free')

        conflict.shared.joinpath('hook.js').write_bytes(KNOWN_SHARED)
        before = conflict.runtime_snapshot()
        lines, _ = conflict.manager('status', 'monet')
        check(lines == ['shared'], f'known shared status: {lines}')
        _, output = conflict.manager('status', 'widgets', ok=False)
        check('SHARED_RUNTIME' in output, 'private status refuses shared feature')
        _, output = conflict.manager('install', 'widgets', ok=False)
        check('SHARED_RUNTIME' in output and conflict.runtime_snapshot() == before,
              'known shared runtime refusal is mutation-free')

    if args.section in ('all', 'thirdparty'):
        single = harness('third-single')
        plugin_id = 'dev.example.alpha'
        body = b'// third-party alpha\nglobalThis.alpha = true;'
        install_third(single, plugin_id, body)
        check(single.installed_ids() == (plugin_id,), 'third-party install stores one payload')
        check(single.status(plugin_id) == 'disabled', 'third-party install starts disabled')
        check(all(single.setting(f'codex_{feature}_enabled') == '0' for feature in FEATURES),
              'third-party install keeps the original three feature switches')
        lines, _ = single.manager('enable', plugin_id)
        check(lines == ['ENABLED'], 'third-party enable returns one keyword')
        check(single.feature_enabled(plugin_id), 'third-party directory records enabled state')
        check(json.loads(single.setting('owornate_launcher_plugins')) == {plugin_id: True},
              'third-party settings mask contains the enabled ID')
        check(single.status(plugin_id) == 'ready', 'third-party-only runtime can be ready')
        check(single.marker()['extensions'] == {plugin_id: True},
              'third-party marker carries the extension mask')
        check(single.start_count() == 1 and single.starts_are_serialized(),
              'third-party only uses the single supervisor')
        check(not (single.base / 'theme.jar').exists(), 'third-party-only runtime needs no theme JAR')
        expected = combined(CORE, [], COMMIT, {plugin_id: body})
        check(single.combined() == expected and (single.base / 'hook.js').read_bytes() == expected,
              'third-party hook uses the deterministic register wrapper')
        check(wrapped_third(plugin_id, body) in expected,
              'third-party register wrapper preserves the imported body')
        lines, _ = single.manager('kind', plugin_id)
        check(lines == ['own'], 'kind accepts a third-party ID and reports environment ownership')

        single.write_marker(error='dev.example.alpha failed')
        check(single.status(plugin_id) == 'installed', 'third-party error is never falsely ready')
        single.write_marker(extensions={plugin_id: False})
        check(single.status(plugin_id) == 'installed', 'third-party mask mismatch is not ready')
        single.write_marker()
        single.write_proc(single.current_pid(),
                          maps='7f00-7f01 r-xp 00000000 fe:00 1 /data/local/tmp/frida-agent-32.so\n')
        write_real_service(single)
        lines, _ = single.run('./real-service.sh', 'status')
        check('runtime_live=yes' in lines and lines.count('runtime_live=yes') == 1,
              f'real service validates third-party PID/mapped/mask: {lines}')
        single.write_marker(error='third-party init failed')
        lines, _ = single.run('./real-service.sh', 'status')
        check('runtime_live=no' in lines, 'real service keeps third-party errors not-ready')

        suffix = harness('third-disabled-suffix')
        install_third(suffix, 'dev.example', b'globalThis.parent = true;\n')
        install_third(suffix, 'dev.example.disabled', b'globalThis.suffix = true;\n')
        lines, _ = suffix.manager('enable', 'dev.example')
        check(lines == ['ENABLED'], 'parent ID before suffix ID enables')
        lines, _ = suffix.manager('enable', 'dev.example.disabled')
        check(lines == ['ENABLED'], 'safe ID ending in .disabled enables')
        check((suffix.base / 'modules/dev.example/installed').is_file() and
              (suffix.base / 'modules/dev.example.disabled/installed').is_file(),
              'dev.example and dev.example.disabled coexist as module directories')
        check(json.loads(suffix.setting('owornate_launcher_plugins')) ==
              {'dev.example': True, 'dev.example.disabled': True},
              'both suffix-related IDs appear in the settings mask')
        lines, _ = suffix.manager('disable', 'dev.example.disabled')
        check(lines == ['DISABLED_OR_REMOVED'], 'suffix ID can be disabled independently')
        check(suffix.feature_enabled('dev.example') and
              not suffix.feature_enabled('dev.example.disabled'),
              'suffix .disabled marker belongs to the third-party directory schema')
        check((suffix.base / 'modules/dev.example.disabled/disabled').is_file() and
              (suffix.base / 'modules/dev.example/disabled').exists() is False,
              'disabling dev.example.disabled does not touch dev.example')
        suffix_hook = (suffix.base / 'hook.js').read_bytes()
        check(suffix_hook.index(b"register('dev.example'") <
              suffix_hook.index(b"register('dev.example.disabled'"),
              'prefix and suffix IDs sort deterministically')

        multi = harness('third-multiple')
        ids = ('zz.example.gamma', 'com.example.alpha', 'org.example.beta')
        bodies = {plugin_id: f'// {plugin_id}\nglobalThis.plugin = {index};\n'.encode()
                  for index, plugin_id in enumerate(ids, 1)}
        for plugin_id in ids:
            install_third(multi, plugin_id, bodies[plugin_id])
        for plugin_id in ids:
            lines, _ = multi.manager('enable', plugin_id)
            check(lines == ['ENABLED'], f'enable {plugin_id}')
        hook = (multi.base / 'hook.js').read_bytes()
        positions = [hook.index(f"register('{plugin_id}'".encode()) for plugin_id in sorted(ids)]
        check(positions == sorted(positions), 'third-party hooks use LC_ALL=C ID order')
        check(hook == combined(CORE, [], COMMIT, bodies), 'multi-plugin composition is complete')
        saved = {plugin_id: ((multi.base / 'payloads' / plugin_id / 'hook.js').read_bytes(),
                             (multi.base / 'payloads' / plugin_id / 'hook.sha256').read_bytes())
                 for plugin_id in ids}
        replacement = b'globalThis.alpha = 2;\n'
        multi.set_plugin('com.example.alpha', replacement)
        lines, _ = multi.manager('update', 'com.example.alpha')
        check(lines == ['UPDATED'], 'update one third-party target')
        check((multi.base / 'payloads/com.example.alpha/hook.js').read_bytes() == replacement,
              'selected third-party payload updates')
        check(all(((multi.base / 'payloads' / plugin_id / 'hook.js').read_bytes(),
                    (multi.base / 'payloads' / plugin_id / 'hook.sha256').read_bytes()) == saved[plugin_id]
                  for plugin_id in ids if plugin_id != 'com.example.alpha'),
              'third-party update preserves other payloads and receipts')
        check(all(multi.feature_enabled(plugin_id) for plugin_id in ids),
              'third-party update preserves enabled choices')
        lines, _ = multi.manager('disable', 'org.example.beta')
        check(lines == ['DISABLED_OR_REMOVED'], 'disable one third-party ID')
        check(multi.feature_enabled('com.example.alpha') and
              multi.feature_enabled('zz.example.gamma') and
              not multi.feature_enabled('org.example.beta'),
              'third-party disable preserves other modules')
        check(b"register('org.example.beta'" in (multi.base / 'hook.js').read_bytes(),
              'disabled third-party body remains in the composition')
        check(json.loads(multi.setting('owornate_launcher_plugins')) == {
            'com.example.alpha': True,
            'org.example.beta': False,
            'zz.example.gamma': True,
        }, 'third-party disabled state remains in JSON settings')
        for plugin_id in ids:
            lines, _ = multi.manager('uninstall', plugin_id)
            check(lines == ['DISABLED_OR_REMOVED'], f'uninstall {plugin_id}')
        check(multi.installed_ids() == (), 'third-party uninstall removes every selected payload')
        check(not any((multi.base / name).exists() for name in
                      ('hook.js', 'runtime-version', 'runtime-core.js', 'runtime-commit.js',
                       'runtime-service.sh', 'hook.sha256', 'payloads')),
              'last third-party removal clears runtime payloads')
        check((multi.base / 'manager-ready').exists() and
              (multi.base / 'frida-inject').read_bytes() == FRIDA,
              'last third-party removal retains the base environment')
        check(json.loads(multi.setting('owornate_launcher_plugins')) == {},
              'last third-party removal writes an empty extension mask')
        check(all(multi.status(plugin_id) == 'available' for plugin_id in ids),
              'third-party IDs return available after cleanup')

        bad = harness('third-invalid')
        bad.set_plugin('dev.example.missing')
        before = bad.runtime_snapshot()
        _, output = bad.manager('install', 'dev.example.missing', ok=False)
        check('PLUGIN_REQUIRED:dev.example.missing' in output and bad.runtime_snapshot() == before,
              'missing third-party source is mutation-free')
        bad.set_plugin('dev.example.empty', b'')
        _, output = bad.manager('install', 'dev.example.empty', ok=False)
        check('PLUGIN_INVALID:dev.example.empty' in output and bad.runtime_snapshot() == before,
              'empty third-party source is mutation-free')
        invalid_ids = ('dev.Example.bad', 'single', 'dev.example..bad', 'dev.example/bad',
                       'dev.example:bad', 'dev.owornate.monet', 'monet.disabled', 'a' * 81)
        for invalid in invalid_ids:
            _, output = bad.manager('status', invalid, ok=False)
            check('INVALID_FEATURE' in output, f'invalid plugin ID rejected: {invalid}')
        check(bad.runtime_snapshot() == before, 'invalid third-party IDs cannot mutate runtime files')

        tampered = harness('third-tampered')
        install_third(tampered, 'dev.example.tampered', b'globalThis.tampered = true;\n')
        (tampered.base / 'payloads/dev.example.tampered/hook.js').write_bytes(b'// changed\n')
        check(tampered.status('dev.example.tampered') == 'conflict',
              'third-party payload receipt mismatch is conflict')
        before = tampered.runtime_snapshot()
        _, output = tampered.manager('update', 'dev.example.tampered', ok=False)
        check('UNKNOWN_FILES' in output and tampered.runtime_snapshot() == before,
              'tampered third-party payload cannot be updated')

        linked = harness('third-symlink')
        install_third(linked, 'dev.example.linked', b'globalThis.linked = true;\n')
        target = linked.root / 'installed-target'
        target.touch()
        marker = linked.base / 'modules/dev.example.linked/installed'
        marker.unlink()
        try:
            os.symlink(target, marker)
        except OSError:
            pass
        else:
            check(linked.status('dev.example.linked') == 'conflict',
                  'third-party installed symlink is rejected')

        shared_third = harness('third-shared')
        shared_third.set_plugin('dev.example.shared', b'globalThis.shared = true;\n')
        shared_third.shared.joinpath('hook.js').write_bytes(KNOWN_SHARED)
        check(shared_third.status('dev.example.shared') == 'shared',
              'third-party status reports shared environment')
        before = shared_third.runtime_snapshot()
        _, output = shared_third.manager('install', 'dev.example.shared', ok=False)
        check('SHARED_RUNTIME' in output and shared_third.runtime_snapshot() == before,
              'shared third-party status is read-only')

    if args.section in ('all', 'idconflict'):
        id_conflict = harness('id-conflict')
        before = id_conflict.runtime_snapshot()
        _, output = id_conflict.manager('status', 'monet.disabled', ok=False)
        check('INVALID_FEATURE' in output and id_conflict.runtime_snapshot() == before,
              'reserved monet.disabled ID is rejected without mutation')

    if args.section in ('all', 'v3migration'):
        v3 = harness('released-v3')
        v3.make_v3(disabled=('widgets',))
        lines, _ = v3.manager('kind')
        check(lines == ['own'], 'released v3 tuple is recognized as owned')
        for feature in FEATURES:
            check(v3.status(feature) == 'update', 'released v3 feature reports update')
        v3.remove_plugins()
        lines, _ = v3.manager('disable', 'monet')
        check(lines == ['DISABLED_OR_REMOVED'], 'first v3 mutation migrates to v4')
        check((v3.base / 'runtime-version').read_text().strip() == '4',
              'v3 migration publishes runtime-version=4')
        check(v3.installed_features() == FEATURES, 'v3 migration keeps all deployed features')
        check(not v3.feature_enabled('monet') and not v3.feature_enabled('widgets') and
              v3.feature_enabled('capture'), 'v3 migration keeps each disable choice')
        check((v3.base / 'theme.jar').exists(), 'v3 migration keeps deployed monet theme')
        check(v3.combined() == combined(CORE, [PLUGIN[f] for f in FEATURES], COMMIT),
              'v3 migration rebuilds v4 from deployed payloads without imports')

        for label, mutate in (
            ('core', lambda case: (case.base / 'runtime-core.js').write_bytes(CORE)),
            ('commit', lambda case: (case.base / 'runtime-commit.js').write_bytes(COMMIT)),
            ('service', lambda case: (case.base / 'runtime-service.sh').write_bytes(SERVICE)),
            ('receipt', lambda case: (case.base / 'hook.sha256').write_text('0' * 64 + '\n')),
        ):
            mixed_v3 = harness(f'v3-mixed-{label}')
            mixed_v3.make_v3()
            mutate(mixed_v3)
            check(mixed_v3.status('monet') == 'conflict', f'mixed v3 {label} pair is refused')
            before = mixed_v3.runtime_snapshot()
            _, output = mixed_v3.manager('update', 'widgets', ok=False)
            check('UNKNOWN_FILES' in output and mixed_v3.runtime_snapshot() == before,
                  f'mixed v3 {label} refusal is mutation-free')

        v3_third = harness('v3-third-layout')
        v3_third.make_v3()
        (v3_third.base / 'modules/dev.example').mkdir()
        (v3_third.base / 'modules/dev.example/installed').touch()
        check(v3_third.status('monet') == 'conflict',
              'v3 refuses third-party directory state before migration')

    if args.section in ('all', 'wrappers'):
        # Wrappers delegate to v4 and create no WatchHide hook.
        wrappers = harness('wrappers')
        (wrappers.system / 'sys_process_switch_list.xml').write_text(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<process_switch_list>\n'
            '  <allow_process>com.example.keep</allow_process>\n'
            '</process_switch_list>\n', encoding='utf-8', newline='\n')
        check(not (wrappers.watchhide / 'hook.js').exists(), 'wrapper fixture has no WatchHide Hook')
        lines, _ = wrappers.widget('runtime-kind')
        check(lines == ['own'], 'widget wrapper delegates runtime-kind')
        lines, _ = wrappers.widget('status')
        check(lines == ['available'], 'widget wrapper delegates available status')
        lines, _ = wrappers.widget('check')
        check(lines == ['available', 'missing', 'own'], f'widget check delegation: {lines}')
        lines, _ = wrappers.widget('capture-status')
        check(lines == ['available'], 'widget wrapper delegates capture-status')
        lines, _ = wrappers.widget('capture-check')
        check(lines == ['available', 'own'], f'widget capture-check delegation: {lines}')
        lines, _ = wrappers.widget('install')
        check(lines == ['ready', 'added', 'own'], f'widget install delegation: {lines}')
        check(wrappers.feature_enabled('widgets') and not (wrappers.watchhide / 'hook.js').exists(),
              'widget install stays self-owned and creates no WatchHide hook')
        check(wrappers.installed_features() == ('widgets',), 'wrapper install stores only selected payload')
        lines, _ = wrappers.widget('disable')
        check(lines == ['disabled', 'present', 'own'], f'widget disable delegation: {lines}')
        check((wrappers.base / 'hook.js').exists() and (wrappers.base / 'runtime-service.sh').exists(),
              'widget disable preserves self-owned files')
        lines, _ = wrappers.widget('enable')
        check(lines == ['ready', 'present', 'own'], f'widget enable delegation: {lines}')
        lines, _ = wrappers.widget('capture-install')
        check(lines == ['ready', 'present'], f'capture-install delegation: {lines}')
        check(wrappers.feature_enabled('capture'), 'capture-install enables capture')
        lines, _ = wrappers.widget('capture-status')
        check(lines == ['ready'], 'capture status ready after wrapper install')
        lines, _ = wrappers.widget('capture-check')
        check(lines == ['ready', 'own'], f'capture-check ready delegation: {lines}')
        lines, _ = wrappers.widget('capture-disable')
        check(lines == ['disabled'], 'capture-disable delegates')
        check((wrappers.base / 'hook.js').exists(), 'capture disable retains runtime files')
        lines, _ = wrappers.widget('capture-enable')
        check(lines == ['ready'], 'capture-enable delegates')
        lines, _ = wrappers.module('status')
        check(lines == ['available'], 'module wrapper delegates monet status')
        lines, _ = wrappers.module('kind')
        check(lines == ['own'], 'module wrapper delegates runtime kind')
        lines, _ = wrappers.module('install')
        check(lines == ['INSTALLED'], f'module install delegation: {lines}')
        lines, _ = wrappers.module('enable')
        check(lines == ['ENABLED'], f'module enable delegation: {lines}')
        lines, _ = wrappers.module('status')
        check(lines == ['installed'], 'module status after delegated enable')

        before = wrappers.runtime_snapshot()
        _, output = wrappers.manager('bogus', 'monet', ok=False)
        check('INVALID_ACTION' in output, 'manager rejects unknown action')
        _, output = wrappers.manager('status', 'foreign-feature', ok=False)
        check('INVALID_FEATURE' in output, 'manager rejects unknown feature')
        _, output = wrappers.service('foreign-command', ok=False, rc=2)
        check('Usage:' in output, 'runtime service rejects unknown command')
        _, output = wrappers.widget('foreign-command', ok=False)
        check('ACTION_INVALID' in output, 'widget wrapper rejects unknown action')
        check(wrappers.runtime_snapshot() == before, 'CLI rejection paths are mutation-free')

    print(f'Runtime lifecycle: {count}/{count} checks passed (simulated Android v4; {args.section})')
