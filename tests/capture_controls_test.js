// Execute the real append block against a narrow Frida/Android bridge fixture.
// Tests dispatch and ordering, not Android class registration or rendering.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../widget-host/capture-controls.js'), 'utf8');
let passed = 0;

function fixture(phase = 'idle', options = {}) {
  if (phase && typeof phase === 'object') { options = phase; phase = 'idle'; }
  const classes = new Map(), registered = new Map(), events = [], timers = [];
  const logs = [], loaderUses = [], factoryGets = [], enumerations = [], refreshes = [];
  let now = 0;
  function method(native, previous) {
    const wrapper = function (...args) { return native.apply(this, args); };
    wrapper.overload = () => wrapper;
    wrapper.implementation = previous || null;
    return wrapper;
  }
  function bridgeObject(name, loader, members = {}) {
    const value = Object.assign({}, members);
    value.getClass = () => ({
      getName: () => name,
      getClassLoader: () => loader,
      getSuperclass: () => null,
      getDeclaredField(field) {
        return {
          setAccessible() {},
          get(object) {
            if (!(field in object)) throw new Error('missing field ' + field);
            return object[field];
          },
          set(object, next) { object[field] = next; },
          setInt(object, next) { object[field] = next; }
        };
      }
    });
    return value;
  }
  function makeLoader(label) {
    return { toString: () => `DexClassLoader[path=${label}]` };
  }
  function controlCount(node, seen = new Set()) {
    if (!node || seen.has(node)) return 0;
    seen.add(node);
    let count = node.isCaptureButton ? 1 : 0;
    const size = typeof node.getChildCount === 'function' ? node.getChildCount() : 0;
    for (let i = 0; i < size; i++) count += controlCount(node.getChildAt(i), seen);
    return count;
  }
  function makeTheme(loader) {
    const theme = { bindCount: 0, observations: [] };
    theme.bind = root => {
      theme.bindCount++;
      theme.observations.push(controlCount(root));
    };
    return theme;
  }
  const targetPath = '/data/local/tmp/watchface-theme/theme.jar';
  const targetLoader = makeLoader(targetPath);
  const wrongLoaders = [
    makeLoader('/data/local/tmp/watchface-theme/native-helper.jar'),
    makeLoader('/system/framework/framework.jar')
  ];
  const defaultLoaders = options.loaders ? options.loaders.slice() :
    options.wrongOnly ? wrongLoaders.slice() :
      options.wrongFirst ? [...wrongLoaders, targetLoader] : [targetLoader];
  const themes = new Map([...defaultLoaders, targetLoader, ...wrongLoaders]
    .map(loader => [loader, makeTheme(loader)]));
  const targetTheme = themes.get(targetLoader), wrongTheme = themes.get(wrongLoaders[0]);
  const globalTheme = makeTheme('Java.use');

  const nativeIcon = bridgeObject('android.graphics.drawable.ColorDrawable');
  const appResources = {
    getIdentifier(name, type) { assert.equal(type, 'drawable'); return 1; },
    getDrawable: method(id => { assert.equal(id, 1); return nativeIcon; })
  };
  const resolver = {
    registerContentObserver: method(() => {}),
    unregisterContentObserver: method(() => {})
  };
  const display = { getDisplayMetrics: () => ({ density: { value: 2 } }) };
  const context = {
    getContentResolver: () => resolver,
    getPackageManager: () => packageManager,
    getResources: () => ({ getIdentifier(name) { return name === 'tile_toggle_setting' ? 10 :
      name === 'tile_image' ? 11 : 0; }, ...display })
  };
  const packageManager = {
    getPackageInfo: method(() => ({ versionCode: 19 })),
    resolveActivity: method(() => ({})),
    getResourcesForApplication: method(() => appResources)
  };
  function makeButton() {
    const button = bridgeObject('com.heytap.wearable.launcher.statusbar.view.LauncherToggleButton', null, {
      isCaptureButton: true, icon: nativeIcon,
      getResources: () => display,
      getChildCount: () => 0,
      getIconDrawable() { return this.icon; },
      setIconDrawable(value) { this.icon = value; },
      setContentDescription(value) { this.description = value; },
      setChecked() {}, setEnableState() {}, setEnabled() {}, setClickable() {},
      setOnClickListener() {}, setOnLongClickListener() {}
    });
    return button;
  }
  const list = {
    items: [], size() { return this.items.length; }, get(i) { return this.items[i]; },
    add(value) { this.items.push(value); },
    remove(i) { this.items.splice(i, 1); },
    clear() { this.items.length = 0; }
  };
  const page = bridgeObject('e.e.c.b.u.h.J', null, {
    children: [],
    getChildCount() { return this.children.length; },
    getChildAt(i) { return this.children[i]; },
    addView(view) {
      if (view.parent && view.parent !== this)
        view.parent.removeView(view);
      if (!this.children.includes(view)) this.children.push(view);
      view.parent = this;
    },
    removeView(view) {
      this.children = this.children.filter(child => child !== view);
      if (view.parent === this) view.parent = null;
    },
    requestLayout() {}, invalidate() {}
  });
  page.h = list;
  const pages = { items: [bridgeObject('e.e.c.b.u.h.H'), page],
    size() { return this.items.length; }, get(i) { return this.items[i]; } };
  const control = bridgeObject('e.e.c.b.u.h.I');
  control.d = pages;
  const root = bridgeObject('android.widget.FrameLayout', null, {
    children: [page],
    getChildCount() { return this.children.length; },
    getChildAt(i) { return this.children[i]; }
  });
  const panel = bridgeObject('com.heytap.wearable.launcher.statusbar.view.StatusPanelView', null, {
    getResources: () => ({ getIdentifier(name, type, pkg) {
      assert.equal(name, 'status_bar'); assert.equal(type, 'id');
      assert.equal(pkg, 'com.heytap.wearable.launcher'); return 42;
    } }),
    findViewById: method(id => { assert.equal(id, 42); return root; }),
    getContext: () => context,
    equals: other => panel === other
  });
  panel.b = control;
  for (const name of ['onViewStartShowing', 'onViewAllShow', 'onViewGone'])
    panel[name] = method(() => events.push(name));
  classes.set('com.heytap.wearable.launcher.statusbar.view.StatusPanelView', panel);
  classes.set('android.util.Log', { i(_tag, message) { logs.push(String(message)); } });
  classes.set('android.provider.Settings$System', {
    getString: method(() => phase),
    getInt: method(() => options.enabled === false ? 0 : 1),
    getUriFor: method(key => key)
  });
  classes.set('android.database.ContentObserver', {});
  classes.set('android.os.Handler', { $new: () => ({}) });
  classes.set('android.os.Looper', { getMainLooper: () => ({}) });
  classes.set('android.graphics.Paint', { $new: () => ({ setColor() {}, setAlpha() {} }) });
  classes.set('android.graphics.drawable.Drawable', {});
  classes.set('android.view.LayoutInflater', { from: () => ({ inflate() {
    const content = bridgeObject('android.widget.LinearLayout', null, {
      children: [], parent: null,
      getChildCount() { return this.children.length; },
      getChildAt(i) { return this.children[i]; },
      findViewById() { return this.children[0]; },
      getParent() { return this.parent; }
    });
    const button = makeButton();
    button.parent = content;
    content.children.push(button);
    return content;
  } }) });
  const oldRender = function (list) { events.push(['v11-render', list]); return 19; };
  classes.set('e.e.c.b.u.h.G', { a: method(() => { throw Error('bypassed v11'); }, oldRender) });
  classes.set('e.e.c.b.u.f.a.Q', { g: method(() => {}, () => events.push('v11-nfc')) });
  classes.set('e.e.c.b.A.a.f', {
    e: method(() => ({})), a: method(tag => events.push(['close', tag]))
  });
  classes.set('android.content.ComponentName', { $new: (pkg, cls) => ({ pkg, cls }) });
  classes.set('android.content.Intent', { $new() {
    const intent = { extras: {}, setComponent(c) { this.component = c; }, addFlags(f) { this.flags = f; } };
    intent.putExtra = method(function (key, value) { this.extras[key] = value; });
    return intent;
  } });
  classes.set('android.app.ActivityThread', { currentApplication: () => ({
    startActivity(intent) { events.push(['launch', intent]); }
  }) });
  const Java = {
    perform(fn) { fn(); }, scheduleOnMainThread(fn) { fn(); },
    retain(value) { return value; },
    enumerateClassLoadersSync() { enumerations.push(true); return defaultLoaders.slice(); },
    ClassFactory: { get(loader) {
      factoryGets.push(loader);
      return { use(name) {
        loaderUses.push({ loader, name });
        const theme = themes.get(loader);
        if (!theme) throw new Error('unknown loader');
        return theme;
      } };
    } },
    use(name) {
      assert.notEqual(name, 'android.content.ContentObserver', 'ContentObserver belongs to android.database');
      if (name === 'dev.codex.watchtheme.ControlTheme') return globalTheme;
      if (!classes.has(name)) classes.set(name, {}); return classes.get(name);
    },
    cast(value, type) {
      if (type === classes.get('java.lang.Integer') && typeof value === 'number')
        return { intValue: () => value };
      return value;
    },
    registerClass(spec) { registered.set(spec.name, spec); return { $new(...args) {
      if (spec.name === 'dev.codex.owornate.CaptureStopDrawable')
        assert.equal(args[0], 48, 'Frida DisplayMetrics.density is accessed through its value field');
      const instance = bridgeObject(spec.name);
      instance.$super = { $init(context) { if (context) instance.b = context; } };
      for (const [name, type] of Object.entries(spec.fields || {}))
        instance[name] = type === 'int' ? 0 : { value: null };
      for (const [name, value] of Object.entries(spec.methods || {})) {
        if (name !== '$init' && typeof value === 'function') instance[name] = value;
        if (name !== '$init' && Array.isArray(value) && value[0]?.implementation)
          instance[name] = value[0].implementation;
      }
      const init = spec.methods?.$init?.implementation;
      if (init) init.apply(instance, args);
      return instance;
    } }; }
  };
  vm.runInNewContext(source, { Java, rpc: { exports: {
    themerefresh() { refreshes.push(true); }
  } }, setTimeout(fn, ms) { timers.push({ at: now + ms, fn }); } });
  assert(registered.has('dev.codex.owornate.CaptureLongPress'), 'append block initialized');
  function advance(ms) {
    const until = now + ms;
    while (true) {
      timers.sort((a, b) => a.at - b.at);
      if (!timers.length || timers[0].at > until) break;
      const task = timers.shift(); now = task.at; task.fn();
    }
    now = until;
  }
  return { classes, registered, panel, page, list, root, events, advance,
    targetLoader, targetTheme, wrongTheme, globalTheme, themes, logs, loaderUses, factoryGets,
    enumerations, refreshes, addButton() { const button = makeButton(); page.addView(button); return button; } };
}

for (const action of ['screenshot', 'record']) {
  const f = fixture();
  const longPress = f.registered.get('dev.codex.owornate.CaptureLongPress').methods.onLongClick;
  assert.equal(longPress.call({ captureAction: { value: action } }, {}), true, 'long press consumes gesture');
  assert.equal(f.events.filter(e => e[0] === 'launch').length, 0, 'no activity before drawer closes');
  f.panel.onViewGone.implementation.call({});
  f.advance(159);
  assert.equal(f.events.filter(e => e[0] === 'launch').length, 0, 'wait for settled frame');
  f.advance(1);
  const launches = f.events.filter(e => e[0] === 'launch');
  assert.equal(launches.length, 1);
  assert.equal(launches[0][1].extras.action, action + '-settings', 'long press opens settings, not capture');
  assert.equal(launches[0][1].component.pkg, 'dev.codex.watchworkshop');
  f.advance(1000);
  assert.equal(f.events.filter(e => e[0] === 'launch').length, 1, 'timeout does not duplicate dispatch');
  passed++;
}
{
  const f = fixture();
  f.registered.get('dev.codex.owornate.CaptureLongPress').methods.onLongClick
    .call({ captureAction: { value: 'screenshot' } }, {});
  f.advance(1000);
  assert.equal(f.events.filter(e => e[0] === 'launch').length, 0, 'failed drawer close never starts capture');
  passed++;
}
{
  const f = fixture(), input = {};
  assert.equal(f.classes.get('e.e.c.b.u.h.G').a.implementation.call({}, input), 19);
  assert.equal(f.events[0][0], 'v11-render');
  assert.equal(f.events[0][1], input, 'original render receives unchanged list');
  passed++;
}
{
  const f = fixture(), methods = f.registered.get('dev.codex.owornate.CaptureTileController').methods;
  let updates = 0;
  const controller = { refreshState() { updates++; } };
  methods.o.find(m => m.argumentTypes.length === 1).implementation.call(controller, false);
  assert.equal(updates, 0, 'native Rx o(boolean) must not touch the view');
  methods.v.implementation.call(controller);
  assert.equal(updates, 1, 'native main-thread v() refreshes the view');
  passed++;
}
{
  const f = fixture('stopping'), methods = f.registered.get('dev.codex.owornate.CaptureTileController').methods;
  const context = { getContentResolver() { return {}; } }, enabled = [];
  const button = {
    getResources() { return { getDisplayMetrics() { return { density: { value: 2 } }; } }; },
    setIconDrawable() {}, setContentDescription() {}, setChecked() {},
    setEnableState(value) { enabled.push(value); }, setEnabled(value) { enabled.push(value); }
  };
  const controller = { captureButton: { value: button }, captureAction: { value: 'record' },
    getClass() { return { getDeclaredField() { return { setAccessible() {}, get() { return context; } }; } }; }
  };
  methods.refreshState.call(controller);
  assert.deepEqual(enabled, [true, true], 'stopping remains clickable and long-clickable');
  methods.performCapture.call(controller);
  f.panel.onViewGone.implementation.call({});
  f.advance(160);
  assert.equal(f.events.find(e => e[0] === 'launch')[1].extras.action, 'record', 'stopping can retry native stop');
  passed++;
}
{
  const f = fixture(), methods = f.registered.get('dev.codex.owornate.CaptureTileController').methods;
  methods.performCapture.call({ captureAction: { value: 'screenshot' } });
  f.advance(300);
  assert.equal(f.events.filter(e => e[0] === 'launch').length, 0, 'short screenshot waits for drawer gone');
  f.panel.onViewGone.implementation.call({});
  f.advance(31);
  assert.equal(f.events.filter(e => e[0] === 'launch').length, 0);
  f.advance(1);
  assert.equal(f.events.find(e => e[0] === 'launch')[1].extras.action, 'screenshot');
  passed++;
}
{
  const f = fixture();
  f.panel.onViewAllShow.implementation.call(f.panel);
  assert.equal(f.list.size(), 2, 'onViewAllShow appended both capture controls');
  assert.equal(f.loaderUses.length, 1);
  assert.equal(f.loaderUses[0].loader, f.targetLoader, 'the actual theme.jar loader was selected');
  assert.equal(f.loaderUses[0].name, 'dev.codex.watchtheme.ControlTheme');
  assert.equal(f.targetTheme.bindCount, 1, 'theme binds without a pre-tinted button icon');
  assert.equal(f.globalTheme.bindCount, 0, 'the default loader fallback is not used');
  assert.deepEqual(f.targetTheme.observations, [2]);
  passed++;
}
{
  const f = fixture({ wrongFirst: true });
  f.panel.onViewAllShow.implementation.call(f.panel);
  assert.deepEqual(f.factoryGets, [f.targetLoader], 'other DexClassLoaders are skipped');
  assert.equal(f.targetTheme.bindCount, 1);
  assert.equal(f.wrongTheme.bindCount, 0, 'a non-theme helper loader never supplies ControlTheme');
  assert.equal(f.globalTheme.bindCount, 0, 'the default loader fallback is not used');
  passed++;
}
{
  const f = fixture();
  f.panel.onViewAllShow.implementation.call(f.panel);
  assert.deepEqual(f.targetTheme.observations, [2]);
  f.panel.onViewGone.implementation.call(f.panel);
  f.addButton();
  f.panel.onViewAllShow.implementation.call(f.panel);
  assert.equal(f.factoryGets.length, 1, 'reopening reuses the cached theme class');
  assert.equal(f.loaderUses.length, 1, 'ControlTheme is resolved once');
  assert.equal(f.targetTheme.bindCount, 2, 'each appended panel is rebound');
  assert.deepEqual(f.targetTheme.observations, [2, 3],
    'the repeated bind sees a button added after the first bind');
  assert.equal(f.globalTheme.bindCount, 0, 'the cached class comes from the actual loader');
  assert.equal(f.list.size(), 2, 'existing capture controllers are reused');
  passed++;
}
{
  const f = fixture({ wrongOnly: true });
  f.panel.onViewAllShow.implementation.call(f.panel);
  assert.equal(f.list.size(), 2, 'append ran before the missing-loader path');
  assert.equal(f.factoryGets.length, 0, 'no non-theme loader is promoted');
  assert.equal(f.targetTheme.bindCount + f.wrongTheme.bindCount + f.globalTheme.bindCount, 0,
    'missing theme.jar loader must not fake a successful bind');
  assert.equal(f.refreshes.length, 0, 'missing loader must not trigger a theme refresh');
  assert(f.logs.some(message => /theme/i.test(message)), 'missing loader is reported');
  passed++;
}
console.log(`Capture controls: ${passed}/${passed} behavior checks passed (bridge fixture)`);
