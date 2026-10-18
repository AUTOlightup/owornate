// Execute the real append block against a narrow Frida/Android bridge fixture.
// Tests dispatch and ordering, not Android class registration or rendering.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../widget-host/capture-controls.js'), 'utf8');
let passed = 0;

function fixture(phase = 'idle') {
  const classes = new Map(), registered = new Map(), events = [], timers = [];
  let now = 0;
  function method(native, previous) {
    const wrapper = function (...args) { return native.apply(this, args); };
    wrapper.overload = () => wrapper;
    wrapper.implementation = previous || null;
    return wrapper;
  }
  const panel = {};
  for (const name of ['onViewStartShowing', 'onViewAllShow', 'onViewGone'])
    panel[name] = method(() => events.push(name));
  classes.set('com.heytap.wearable.launcher.statusbar.view.StatusPanelView', panel);
  classes.set('android.util.Log', { i() {} });
  classes.set('android.provider.Settings$System', { getString: method(() => phase) });
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
    use(name) {
      assert.notEqual(name, 'android.content.ContentObserver', 'ContentObserver belongs to android.database');
      if (!classes.has(name)) classes.set(name, {}); return classes.get(name);
    },
    cast(value) { return value; },
    registerClass(spec) { registered.set(spec.name, spec); return { $new(...args) {
      if (spec.name === 'dev.codex.owornate.CaptureStopDrawable')
        assert.equal(args[0], 48, 'Frida DisplayMetrics.density is accessed through its value field');
      return {};
    } }; }
  };
  vm.runInNewContext(source, { Java, setTimeout(fn, ms) { timers.push({ at: now + ms, fn }); } });
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
  return { classes, registered, panel, events, advance };
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
console.log(`Capture controls: ${passed}/${passed} behavior checks passed (bridge fixture)`);
