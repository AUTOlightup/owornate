// Minimal executable tests for the self-owned Launcher runtime.
// These use narrow Frida/Android mocks; they do not claim device behavior.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.join(__dirname, '..');
const read = relative => fs.readFileSync(path.join(root, relative), 'utf8');
const sourceFiles = Object.freeze({
  core: 'standalone/runtime-core.js',
  monet: 'plugins/launcher/monet/hook.js',
  widgets: 'plugins/launcher/widgets/hook.js',
  capture: 'plugins/launcher/capture/hook.js',
  commit: 'standalone/runtime-commit.js'
});
const sources = Object.fromEntries(
  Object.entries(sourceFiles).map(([name, relative]) => [name, read(relative)])
);
const legacyComposites = new Set([
  'standalone/runtime-base.js',
  'standalone/widget-host.js',
  'standalone/runtime-hook.js'
]);
let passed = 0;
let loadedFiles = [];

function method(native) {
  const wrapper = function (...args) { return native.apply(this, args); };
  wrapper.overload = () => wrapper;
  wrapper.implementation = null;
  return wrapper;
}

function loadCore(flags, options = {}) {
  const writes = [], used = [], themes = [];
  const classes = new Map();
  const settings = {
    getString: { overload: () => ({ call: () => options.extensions === undefined ? null : options.extensions }) },
    getInt: {
      overload: () => ({
        call: (_self, _resolver, key) => {
          if (options.failKey === key) throw new Error('settings unavailable');
          return flags[key] ? 1 : 0;
        }
      })
    }
  };
  classes.set('android.provider.Settings$System', settings);
  classes.set('android.util.Log', { e() {}, i() {} });
  classes.set('android.os.Process', { myPid: () => 4242 });
  classes.set('java.io.File', {
    $new(a, b) {
      const value = b === undefined ? String(a) : String(a) + '/' + String(b);
      return {
        path: value,
        exists: () => true,
        mkdir: () => true,
        getAbsolutePath: () => value
      };
    }
  });
  classes.set('java.io.FileOutputStream', {
    $new(file) {
      return {
        write(bytes) { writes.push({ path: file.path || String(file), text: Buffer.from(bytes).toString('utf8') }); },
        close() {}
      };
    }
  });
  classes.set('java.lang.String', { $new: value => ({ getBytes: () => Buffer.from(String(value), 'utf8') }) });
  classes.set('android.app.ActivityThread', {
    currentApplication: () => ({
      getContentResolver: () => ({}),
      getResources: () => ({ getIdentifier: () => 1 }),
      getPackageName: () => 'com.heytap.wearable.launcher',
      getCodeCacheDir: () => '/cache',
      getClassLoader: () => ({})
    })
  });
  classes.set('dalvik.system.DexClassLoader', {
    $new(loadPath) { themes.push(loadPath); return { loadPath }; }
  });
  const G = {};
  G.a = method(() => {});
  G.initView = method(() => {});
  classes.set('e.e.c.b.u.h.G', G);
  classes.set('e.e.c.b.u.e.b', { a: method(() => {}) });
  classes.set('android.view.View', {});
  const candidate = {
    start() {}, status: () => '{"ready":true}', bind() {}, beginDetails() {}, endDetails() {}, bindDetails() {}
  };
  const registered = [];
  const Java = {
    perform(fn) { fn(); },
    scheduleOnMainThread(fn) { fn(); },
    retain(value) { return value; },
    cast(value) { return value; },
    choose(_name, callbacks) { if (callbacks.onComplete) callbacks.onComplete(); },
    registerClass(spec) {
      registered.push(spec && spec.name);
      const implementation = function () {};
      implementation.$new = function () { return {}; };
      return implementation;
    },
    enumerateClassLoadersSync() { return []; },
    use(name) { used.push(name); return classes.get(name) || {}; },
    ClassFactory: { get: () => ({ use: () => candidate }) }
  };
  const context = { console, Promise, module: { exports: {} }, rpc: { exports: {} } };
  context.globalThis = context;
  context.Java = Java;
  const loaded = {
    context, Java, writes, used, themes, registered,
    get runtime() { return context.__watchWorkshop; }
  };
  runPart('core', loaded);
  return loaded;
}

function runPart(name, loaded) {
  if (!Object.prototype.hasOwnProperty.call(sources, name)) {
    throw new Error('unknown split source: ' + name);
  }
  loadedFiles.push(sourceFiles[name]);
  vm.runInNewContext(sources[name], loaded.context, { filename: sourceFiles[name] });
  return loaded;
}

{
  loadedFiles = [];
  const f = loadCore({ codex_monet_enabled: 0, codex_widgets_enabled: 1, codex_capture_enabled: 1 });
  assert.equal(f.themes.length, 0, 'Monet disabled must not start the theme helper');
  assert.equal(f.used.includes('dalvik.system.DexClassLoader'), false);
  assert.equal(f.writes.length, 0, 'marker is deferred until bootstrap commit');
  assert.deepEqual(loadedFiles, [sourceFiles.core], 'core is loaded independently');
  runPart('commit', f);
  assert.equal(f.writes.length, 1);
  assert.equal(f.writes[0].path, '/data/user_de/0/com.heytap.wearable.launcher/files/watchworkshop-runtime.json');
  const marker = JSON.parse(f.writes[0].text);
  assert.deepEqual(Object.keys(marker), ['version', 'pid', 'error', 'monet', 'widgets', 'capture', 'extensions']);
  assert.deepEqual(marker, {
    version: 2, pid: 4242, error: '', monet: false, widgets: true, capture: true, extensions: {}
  });
  f.runtime.report('widgets', new Error('registry failed'));
  assert.equal(f.writes.length, 2, 'post-init errors refresh the unified marker');
  assert.match(JSON.parse(f.writes[1].text).error, /^widgets: (Error: )?registry failed/);
  assert.deepEqual(loadedFiles, [sourceFiles.core, sourceFiles.commit]);
  passed++;
}

{
  loadedFiles = [];
  const f = loadCore({ codex_monet_enabled: 0, codex_widgets_enabled: 0, codex_capture_enabled: 0 },
    { failKey: 'codex_widgets_enabled' });
  runPart('commit', f);
  const marker = JSON.parse(f.writes[0].text);
  assert.equal(marker.widgets, false);
  assert.match(marker.error, /^bootstrap: codex_widgets_enabled: settings unavailable/);
  assert.equal(f.themes.length, 0);
  passed++;
}

{
  loadedFiles = [];
  const f = loadCore({ codex_monet_enabled: 1, codex_widgets_enabled: 0, codex_capture_enabled: 0 });
  assert.equal(f.runtime.enabled('monet'), true);
  assert.equal(f.themes.length, 0, 'core alone must not load the theme helper');
  assert.equal(f.used.includes('dalvik.system.DexClassLoader'), false,
    'core alone must not touch the theme class loader');
  runPart('monet', f);
  assert.deepEqual(f.themes, ['/data/local/tmp/watchworkshop-runtime/theme.jar']);
  assert.equal(f.runtime.enabled('monet'), true);
  assert.equal(f.runtime.enabled('widgets'), false);
  assert.deepEqual(loadedFiles, [sourceFiles.core, sourceFiles.monet]);
  passed++;
}

{
  loadedFiles = [];
  const f = loadCore({ codex_monet_enabled: 0, codex_widgets_enabled: 0, codex_capture_enabled: 0 });
  f.used.length = 0;
  runPart('capture', f);
  assert.equal(f.registered.length, 0, 'disabled capture must not register Java classes');
  assert.deepEqual(f.used, [], 'disabled capture must not resolve feature classes');
  assert.deepEqual(loadedFiles, [sourceFiles.core, sourceFiles.capture]);
  passed++;
}

{
  loadedFiles = [];
  const f = loadCore({ codex_monet_enabled: 0, codex_widgets_enabled: 1, codex_capture_enabled: 0 });
  f.used.length = 0;
  runPart('commit', f);
  assert.equal(f.used.includes('e.e.c.b.n.b'), false,
    'missing widgets module must not fall back to the old composite hook');
  assert.deepEqual(loadedFiles, [sourceFiles.core, sourceFiles.commit]);
  assert.equal(JSON.parse(f.writes[0].text).widgets, true, 'marker still reports requested flag');
  passed++;
}

{
  loadedFiles = [];
  const f = loadCore({ codex_monet_enabled: 0, codex_widgets_enabled: 0, codex_capture_enabled: 0 });
  ['monet', 'widgets', 'capture', 'commit'].forEach(name => runPart(name, f));
  assert.deepEqual(loadedFiles, [
    sourceFiles.core, sourceFiles.monet, sourceFiles.widgets,
    sourceFiles.capture, sourceFiles.commit
  ], 'split assembly loads core, feature hooks, then commit');
  assert.equal(loadedFiles.some(file => legacyComposites.has(file)), false,
    'split assembly never loads an old composite source');
  assert.equal(JSON.parse(f.writes[0].text).error, '');
  passed++;
}

function loadWidgets(enabled, options = {}) {
  const used = [], admitted = [];
  const registry = {};
  const list = {
    values: [],
    contains(value) { return this.values.some(item => item.value === value); },
    add(value) { this.values.push(value); }
  };
  const reports = [];
  registry.field = list;
  registry.getClass = () => ({
    getDeclaredField: () => ({
      setAccessible() {},
      get: () => list
    })
  });
  function registryCall(arg) {
    if (arg === undefined || arg === null) return registry;
    admitted.push(String(arg.value));
    if (options.failAdmission) throw new Error('registry admission failed');
    return true;
  }
  registryCall.overload = (...types) => ({
    call: (_self, value) => types.length === 0 ? registry : registryCall(value)
  });
  const QCV = {
    onViewStartShowing: { overload: () => ({}) },
    onViewAllShow: { overload: () => ({}) }
  };
  const classes = new Map();
  classes.set('e.e.c.b.n.b', { a: registryCall });
  classes.set('java.util.List', {});
  classes.set('java.lang.String', { $new: value => ({ value }) });
  classes.set('android.util.Log', { e() {}, i() {} });
  classes.set('com.heytap.wearable.launcher.quickcenter.view.QuickCenterView', QCV);
  classes.set('android.view.View', {});
  classes.set('android.view.ViewGroup$MarginLayoutParams', {});
  classes.set('android.provider.Settings$System', {
    getInt: { overload: () => ({ call: () => 0 }) }
  });
  const context = {
    __watchWorkshop: {
      enabled(scope) { return scope === 'widgets' ? enabled : false; },
      report(scope, error) { reports.push({ scope, error: String(error) }); }
    }
  };
  const Java = {
    perform(fn) { fn(); },
    use(name) { used.push(name); return classes.get(name) || {}; },
    cast(value) { return value; },
    retain(value) { return value; },
    scheduleOnMainThread(fn) { fn(); },
    choose() {}
  };
  context.globalThis = context;
  context.Java = Java;
  vm.runInNewContext(sources.widgets, context, { filename: sourceFiles.widgets });
  return { used, admitted, list, reports };
}

{
  const f = loadWidgets(false);
  assert.equal(f.used.length, 0, 'widget gate must run before any Java class lookup');
  assert.deepEqual(f.admitted, []);
  assert.deepEqual(f.list.values, []);
  passed++;
}

{
  const f = loadWidgets(true);
  assert.deepEqual(f.admitted, ['dev.codex.watchworkshop']);
  assert.deepEqual(f.list.values.map(item => item.value), ['dev.codex.watchworkshop']);
  assert.equal(f.used.filter(name => name === 'e.e.c.b.n.b').length, 1);
  assert.equal(f.used.includes('com.heytap.wearable.launcher.quickcenter.view.QuickCenterView'), true);
  passed++;
}

{
  const f = loadWidgets(true, { failAdmission: true });
  assert.equal(f.reports.length, 1);
  assert.equal(f.reports[0].scope, 'widgets');
  assert.match(f.reports[0].error, /registry admission failed/);
  passed++;
}

function count(text, needle) {
  return text.split(needle).length - 1;
}

{
  const order = ['core', 'monet', 'widgets', 'capture', 'commit'];
  const assembly = order.map(name => sources[name]).join('\n');
  new vm.Script(assembly, { filename: 'launcher-split-assembly.js' });
  for (const [name, source] of Object.entries(sources)) {
    new vm.Script(source, { filename: sourceFiles[name] });
    assert.equal(legacyComposites.has(sourceFiles[name]), false,
      name + ' is loaded from its split source');
  }
  assert.equal(count(assembly, "log('capture controls ready');"), 1, 'capture controls initialize once');
  assert.equal(count(assembly, "log('native indicator ready');"), 1, 'capture indicator initializes once');
  assert.equal(count(assembly, '/data/local/tmp/watchworkshop-runtime/theme.jar'), 2,
    'monet and capture rebind use the runtime jar');
  assert.equal(assembly.includes('/data/local/tmp/watchface-theme/theme.jar'), false);
  assert.equal(assembly.includes('e.e.c.b.u.f.a.Q'), false, 'no shared NFC restore hook');
  assert.equal(assembly.includes('restoreTiles'), false, 'no shared WatchHide lifecycle');
  assert.equal(assembly.includes('GENERATED by standalone/build-runtime.ps1'), false,
    'split assembly is not the old generated composite');
  const core = assembly.indexOf('WatchWorkshop self-owned Launcher runtime coordinator');
  const monet = assembly.indexOf('launcher-v1: Monet entry');
  const widgets = assembly.indexOf('WatchWorkshop Launcher widget-host gates');
  const controls = assembly.indexOf('// v12 append block');
  const indicator = assembly.indexOf('// v13: an event-driven recording indicator');
  const commit = assembly.indexOf('// Commit after the imported feature hooks');
  assert.ok(core >= 0 && core < monet && monet < widgets && widgets < controls
      && controls < indicator && indicator < commit,
    'assembly order is core, monet, widgets, capture, commit');
  passed++;
}

{
  const f = loadCore({}, { extensions: '{"dev.example.active":true,"dev.example.disabled":false}' });
  const calls = [];
  f.runtime.register('dev.example.active', plugin => {
    calls.push(plugin.id);
    assert.ok(plugin.context.getContentResolver());
    assert.ok(Object.isFrozen(plugin));
  });
  f.runtime.register('dev.example.disabled', () => { throw new Error('disabled code executed'); });
  runPart('commit', f);
  assert.deepEqual(calls, ['dev.example.active']);
  const mark = JSON.parse(f.writes.at(-1).text);
  assert.deepEqual(mark.extensions, { 'dev.example.active': true, 'dev.example.disabled': false });
  assert.equal(mark.error, '');
  passed++;
}

{
  const f = loadCore({}, { extensions: '{"dev.example.bad":true,"dev.example.good":true}' });
  let handle, good = false;
  f.runtime.register('dev.example.bad', () => { throw new Error('failed init'); });
  f.runtime.register('dev.example.good', plugin => { good = true; handle = plugin; });
  runPart('commit', f);
  assert.ok(good, 'failed initialization does not skip the following plugin');
  assert.match(JSON.parse(f.writes.at(-1).text).error, /dev.example.bad: failed init/);
  handle.report(new Error('async failure'));
  assert.match(JSON.parse(f.writes.at(-1).text).error, /dev.example.good: async failure/);
  passed++;
}

{
  const f = loadCore({}, { extensions: '{"dev.example.missing":true}' });
  runPart('commit', f);
  assert.match(JSON.parse(f.writes.at(-1).text).error, /dev.example.missing: Launcher plugin did not register/);
  passed++;
}

for (const invalid of ['[]', '{"dev.example.test":1}', '{"dev.owornate.monet":true}',
  '{"monet.disabled":true}', 'invalid json']) {
  const f = loadCore({}, { extensions: invalid });
  runPart('commit', f);
  assert.match(JSON.parse(f.writes.at(-1).text).error, /bootstrap:/);
  passed++;
}

console.log(`Runtime hook: ${passed}/${passed} flag, registry, marker, split assembly and load-order groups passed`);
