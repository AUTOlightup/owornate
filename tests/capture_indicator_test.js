// Run the actual indicator block against a native-row/lifecycle bridge fixture.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../widget-host/capture-indicator.js'), 'utf8');
function fixture() {
  let phase = 'idle', enabled = 1, lifecycle, observed = [], removed = 0;
  const registered = new Map(), classes = new Map();
  function method(fn) { fn.overload = () => fn; return fn; }
  function object(extra = {}) { return Object.assign({ equals(other) { return this === other; }, $isSameObject(other) { return this === other; } }, extra); }
  const nativeParams = { width: 32, height: 32, margin: 8 };
  const qq = object({ name: 'QQ' }), nativeIcon = object({ getLayoutParams: () => nativeParams });
  function makeRow() {
    const result = object({ children: [nativeIcon, qq], visibility: 0, alpha: 0.75,
      getContext: () => ({ getContentResolver: () => resolver }),
      getResources: () => ({ getDisplayMetrics: () => ({ density: { value: 2 } }) }),
      findViewById: id => id === 2131296463 ? nativeIcon : null,
      addView(icon, params) { icon.parent = result; icon.params = params; result.children.push(icon); },
      removeView(icon) { result.children = result.children.filter(child => child !== icon); icon.parent = null; }
    }); return result;
  }
  const resolver = {
    registerContentObserver: method((key, _descendants, observer) => observed.push({ key, observer })),
    unregisterContentObserver(observer) { removed++; observed = observed.filter(item => item.observer !== observer); }
  };
  let row = makeRow(), layout = null;
  const decor = object({ findViewById: () => row, getViewTreeObserver: () => ({
    isAlive: () => true, addOnGlobalLayoutListener(value) { layout = value; },
    removeOnGlobalLayoutListener(value) { assert.equal(value, layout); layout = null; }
  }) });
  const activity = { getWindow: () => ({ getDecorView: () => decor }) };
  function reflected(values) { return { getClass: () => ({ getDeclaredField: key => ({
    setAccessible() {}, get: () => values[key]
  }) }) }; }
  const map = { values: () => ({ iterator() { let more = true; return {
    hasNext: () => more, next() { more = false; return reflected({ activity }); }
  }; } }) };
  classes.set('android.app.ActivityThread', {
    currentApplication: () => ({ registerActivityLifecycleCallbacks(value) { lifecycle = value; } }),
    currentActivityThread: () => reflected({ mActivities: map })
  });
  classes.set('android.util.Log', { i() {} });
  classes.set('android.widget.LinearLayout', { class: { isInstance: value => value && value.children } });
  classes.set('android.widget.LinearLayout$LayoutParams', {
    class: { isInstance: value => value === nativeParams }, $new: method(value => ({ ...value }))
  });
  classes.set('android.widget.ImageView$ScaleType', { FIT_XY: { value: 'FIT_XY' } });
  classes.set('android.provider.Settings$System', {
    getString: method(() => phase), getInt: method(() => enabled), getUriFor: key => key
  });
  classes.set('android.os.Looper', { getMainLooper: () => ({}) });
  classes.set('android.os.Handler', { $new: value => value });
  classes.set('java.lang.String', { $new: value => value });
  classes.set('android.graphics.drawable.GradientDrawable', { $new: () => ({
    setShape() {}, setColor() {}, setStroke(width) { assert.equal(width, 2); }
  }) });
  classes.set('com.heytap.wearable.launcher.views.CircleImageView2', { $new() { return object({
    visibility: 8, parent: null, getParent() { return this.parent; },
    getVisibility() { return this.visibility; }, setVisibility(value) { this.visibility = value; },
    setImageDrawable() {}, setScaleType() {}, setFocusable() {}, setClickable() {},
    setContentDescription: Object.assign(function (value) { this.description = value; },
      { overload: () => ({ call: (self, value) => { self.description = value; } }) })
  }); } });
  vm.runInNewContext(source, { Java: {
    perform: fn => fn(), scheduleOnMainThread: fn => fn(), retain: value => value, cast: value => value,
    use(name) { if (!classes.has(name)) classes.set(name, {}); return classes.get(name); },
    registerClass(spec) {
      registered.set(spec.name, spec);
      return { $new: () => spec.methods };
    }
  } });
  function change(next, on = enabled) {
    phase = next; enabled = on;
    for (const { observer } of [...observed]) observer.onChange.implementation(false);
  }
  return { get row() { return row; }, qq, nativeIcon, nativeParams, decor, activity, change,
    get lifecycle() { return lifecycle; }, get observed() { return observed; }, get removed() { return removed; },
    layout: () => layout.onGlobalLayout(), replaceRow() { row = makeRow(); }, clearRow() { row = null; }
  };
}
let passed = 0;
{
  const f = fixture(), icon = f.row.children[2];
  assert.equal(f.row.children.length, 3);
  assert.equal(f.row.children[0], f.nativeIcon); assert.equal(f.row.children[1], f.qq);
  assert.equal(icon.description, '正在录屏');
  assert.deepEqual(icon.params, f.nativeParams); assert.notEqual(icon.params, f.nativeParams);
  for (const phase of ['idle', 'starting', 'screenshot', 'error']) {
    f.change(phase); assert.equal(icon.visibility, 8, phase);
  }
  for (const phase of ['recording', 'stopping']) {
    f.change(phase); assert.equal(icon.visibility, 0, phase);
  }
  f.change('recording', 0); assert.equal(icon.visibility, 8, 'module disable hides indicator');
  assert.equal(f.row.visibility, 0); assert.equal(f.row.alpha, 0.75, 'inherits native row animation');
  passed++;
}
{
  const f = fixture();
  for (let i = 0; i < 10; i++) { f.layout(); f.lifecycle.onActivityResumed(f.activity); }
  assert.equal(f.row.children.length, 3); assert.equal(f.observed.length, 2, 'one observer for two settings');
  f.change('recording');
  const previous = f.row;
  f.replaceRow(); f.layout();
  assert.equal(previous.children.length, 2, 'old icon removed without touching QQ');
  assert.equal(f.row.children.length, 3); assert.equal(f.row.children[2].visibility, 0);
  assert.equal(f.removed, 1); assert.equal(f.observed.length, 2);
  f.lifecycle.onActivityDestroyed(f.activity);
  assert.equal(f.row.children.length, 2); assert.equal(f.observed.length, 0);
  f.lifecycle.onActivityResumed(f.activity);
  assert.equal(f.row.children.length, 3); assert.equal(f.row.children[2].visibility, 0, 'recreation reads current phase');
  f.change('idle'); assert.equal(f.row.children[2].visibility, 8);
  passed++;
}
{
  const f = fixture();
  f.lifecycle.onActivityDestroyed(f.activity);
  f.clearRow(); f.lifecycle.onActivityResumed(f.activity);
  assert.equal(f.observed.length, 0);
  f.replaceRow(); f.layout();
  assert.equal(f.row.children.length, 3, 'late inflation attaches through native layout event');
  f.change('recording'); assert.equal(f.row.children[2].visibility, 0);
  passed++;
}
console.log(`Capture indicator: ${passed}/${passed} lifecycle/state groups passed (bridge fixture)`);
