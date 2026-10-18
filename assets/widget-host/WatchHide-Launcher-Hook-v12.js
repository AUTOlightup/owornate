// OPPO Watch 2 A.92 Launcher: change the real view tree, not a screen overlay.
// No Java.registerClass, heap scan on tap, global View hook, or radio changes.
//
// v11 adds one negative-screen (QuickCenter) option: hide the Breeno row when
// Settings.System 'codex_qc_hide_breeno' = 1. No DEX, no adapter hooks; see the
// appended QuickCenter block at the end.
Java.perform(function () {
  const G = Java.use('e.e.c.b.u.h.G');
  const H = Java.use('e.e.c.b.u.h.H');
  const Q = Java.use('e.e.c.b.u.f.a.Q');
  const Net = Java.use('com.heytap.wearable.launcher.statusbar.view.NetSignalView');
  const StatusPresenter = Java.use('e.e.c.b.u.e.b');
  const AppsView = Java.use('com.heytap.wearable.launcher.allapps.view.AllAppsContainerView');
  const AppsPresenter = Java.use('e.e.c.b.a.d.d');
  const Context = Java.use('android.content.Context');
  const View = Java.use('android.view.View');
  const ViewGroup = Java.use('android.view.ViewGroup');
  const JInt = Java.use('java.lang.Integer');
  const ArrayList = Java.use('java.util.ArrayList');
  const CopyOnWriteArrayList = Java.use('java.util.concurrent.CopyOnWriteArrayList');
  const Settings = Java.use('android.provider.Settings$System');
  const Log = Java.use('android.util.Log');
  const key = 'codex_watchhide_native';
  const mobileId = 0x7f09013b;
  const wifiId = 0x7f090119;
  const hiddenTiles = { 0: true, 1: true, 7: true, 9: true, 13: true }; // 9 = BluetoothTile; 13 = SettingTile
  // User-selected app-drawer icons, 2026-09-29. Each package has one entry
  // in the current 40-icon list, so package matching is unambiguous here.
  const hiddenAppPackages = {
    'com.heytap.wearable.dialer': true,          // 1  电话
    'com.heytap.wearable.market': true,          // 7  软件商店
    'com.heytap.wearable.themestore': true,      // 8  表盘商店
    'com.heytap.wearable.breeno': true,          // 11 小布助手
    'com.heytap.wearable.powermanager': true,    // 16 电池管家
    'com.heytap.wearable.wallet': true,          // 19 钱包
    'com.android.mms': true,                     // 20 信息
    'com.heytap.wearable.musiccontroller': true, // 21 音乐控制
    'com.android.settings': true,                // 22 设置
    'com.eg.android.AlipayGphone': true,         // 24 支付宝
    'com.android.shortcuts': true,              // 30 快捷方式
    'com.jami.tool.hiddensetting': true,         // 31 隐藏的设置项
    'com.moxing.owplus': true,                   // 33 OWPlus
    'ml.sky233.zero.music': true,                // 34 零度音乐
    'cn.local.luckinpocket': true,               // 35 取餐码口袋
    'com.tencent.qqlite': true,                  // 36 WearQQ
    'mywft1.weilei.wg_netdisktic': true,        // 37 腕上文件
    'com.oppo1.demo.testapp': true,              // 38 通知转发
    'com.android.stk': true,                     // 39 SIM卡工具包
    'com.example.wristai': true                  // 40 腕上 AI
  };
  let hidden = false;
  let modeLoaded = false;
  let context = null;
  let control = null;
  let net = null;
  let apps = null;
  let fullApps = null;
  let generation = 0;

  function log(message) {
    try { Log.i('WatchHideNative', String(message)); } catch (_) {}
    try { send({ phase: String(message), hidden: hidden }); } catch (_) {}
  }
  function errorText(error) { return String(error) + (error && error.stack ? '\n' + error.stack : ''); }
  function field(object, name) {
    let cls = object.getClass();
    while (cls) {
      try {
        const f = cls.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(object);
      } catch (_) { cls = cls.getSuperclass(); }
    }
    throw new Error('missing field ' + name);
  }
  function tileId(tile) { return Java.cast(field(tile, 'a'), JInt).intValue(); }
  function setContext(value) {
    if (!context) context = Java.retain(Java.cast(value, Context));
    if (!modeLoaded) {
      hidden = Settings.getInt.overload('android.content.ContentResolver', 'java.lang.String', 'int')
        .call(Settings, context.getContentResolver(), key, 0) === 1;
      modeLoaded = true;
      log('loaded hidden=' + hidden);
    }
  }
  function saveMode() {
    return Settings.putInt.overload('android.content.ContentResolver', 'java.lang.String', 'int')
      .call(Settings, context.getContentResolver(), key, hidden ? 1 : 0);
  }
  let theme = null;
  function ensureTheme() {
    if (theme) return theme;
    const Thread = Java.use('android.app.ActivityThread');
    const Loader = Java.use('dalvik.system.DexClassLoader');
    const File = Java.use('java.io.File');
    const app = Thread.currentApplication();
    const cache = File.$new(app.getCodeCacheDir(), 'watchface-theme-v2');
    if (!cache.exists() && !cache.mkdir()) throw new Error('Theme code cache unavailable');
    const loader = Loader.$new('/data/local/tmp/watchface-theme/theme.jar', cache.getAbsolutePath(), null, app.getClassLoader());
    theme = Java.ClassFactory.get(loader).use('dev.codex.watchtheme.ControlTheme');
    theme.start(app);
    return theme;
  }
  function bindTheme(g) {
    const retained = Java.retain(g);
    Java.scheduleOnMainThread(function () {
      try {
        const bridge = ensureTheme();
        const id = context.getResources().getIdentifier('status_bar', 'id', 'com.heytap.wearable.launcher');
        const root = retained.findViewById.overload('int').call(retained, id);
        if (root) bridge.bind(Java.cast(root, View));
      } catch (e) { log('theme bind error: ' + errorText(e)); }
      finally { retained.$dispose(); }
    });
  }
  function themeMain(fn) {
    return new Promise(function (resolve) {
      Java.scheduleOnMainThread(function () {
        try { const bridge=ensureTheme(); fn(bridge); resolve(JSON.parse(String(bridge.status()))); }
        catch(e) { resolve({ ready:false, error:String(e) }); }
      });
    });
  }
  function captureControl(value) {
    control = Java.retain(Java.cast(value, G));
    setContext(field(control, 'mContext'));
    bindTheme(control);
  }
  function captureNet(value) {
    net = Java.retain(Java.cast(value, Net));
    setContext(net.getContext());
  }
  function filteredApps(source) {
    const copy = ArrayList.$new();
    copy.addAll(source);
    if (!hidden) return copy;
    let removed = 0;
    for (let i = copy.size() - 1; i >= 0; i--) {
      const info = copy.get(i);
      if (!hiddenAppPackages[String(field(info, 'a'))]) continue;
      copy.remove(i);
      removed++;
    }
    if (removed) log('removed selected launcher entries=' + removed);
    return copy;
  }
  function rememberApps(list) {
    const copy = ArrayList.$new();
    copy.addAll(list);
    const present = {};
    for (let i = 0; i < copy.size(); i++) {
      present[String(field(copy.get(i), 'a'))] = true;
    }
    if (copy.size() && fullApps && context) {
      // Some OEM callbacks echo the displayed (already filtered) list.
      // Preserve each selected entry while its package remains installed, allowing
      // the next tap to restore the complete list without restarting Launcher.
      let preserved = 0;
      const pm = context.getPackageManager();
      for (let i = 0; i < fullApps.size(); i++) {
        const info = fullApps.get(i);
        const pkg = String(field(info, 'a'));
        if (!hiddenAppPackages[pkg] || present[pkg]) continue;
        try {
          pm.getApplicationInfo(pkg, 0);
          copy.add(Math.min(i, copy.size()), info);
          present[pkg] = true;
          preserved++;
        } catch (_) {}
      }
      if (preserved) log('preserved selected launcher entries=' + preserved);
    }
    fullApps = Java.retain(copy);
  }
  function captureApps(value) {
    apps = Java.retain(Java.cast(value, AppsView));
    setContext(apps.getContext());
  }
  const loadApps = AppsView.onLoadInstalledApp.overload('java.util.List', 'boolean');
  loadApps.implementation = function (list, fresh) {
    try {
      captureApps(this);
      rememberApps(list);
      return loadApps.call(this, filteredApps(fullApps), fresh);
    } catch (e) {
      log('app list filter error: ' + errorText(e));
      return loadApps.call(this, list, fresh);
    }
  };
  function applyApps() {
    if (!apps || !fullApps) return;
    loadApps.call(apps, filteredApps(fullApps), false);
    log('refreshed app list hidden=' + hidden);
  }

  function tilePages(g) { return Java.cast(field(g, 'd'), ArrayList); }
  function hideTiles(g) {
    const pages = tilePages(g);
    if (!pages) return;
    let removed = 0;
    for (let p = 0; p < pages.size(); p++) {
      const page = Java.cast(pages.get(p), H);
      const tiles = Java.cast(field(page, 'h'), ArrayList);
      let pageRemoved = 0;
      for (let i = tiles.size() - 1; i >= 0; i--) {
        const tile = tiles.get(i);
        if (!hiddenTiles[tileId(tile)]) continue;
        page.removeView(Java.cast(field(tile, 'd'), View));
        tiles.remove(i);
        pageRemoved++;
      }
      if (pageRemoved) {
        page.requestLayout();
        page.invalidate();
        removed += pageRemoved;
      }
    }
    if (removed) log('removed control tiles=' + removed);
  }
  const render = G.a.overload('java.util.concurrent.CopyOnWriteArrayList');
  function restoreTiles(g) {
    const presenter = Java.cast(field(g, 'mPresenter'), StatusPresenter);
    const fullList = Java.cast(field(presenter, 'c'), CopyOnWriteArrayList);
    render.call(g, fullList);
    const pages = tilePages(g);
    if (pages) for (let i = 0; i < pages.size(); i++) Java.cast(pages.get(i), H).requestLayout();
    log('restored control tiles');
  }

  const netSet = Net.a.overload('android.view.View', 'int');
  const netWifi = Net.f.overload();
  function applyTop(n) {
    if (!n) return;
    const parent = Java.cast(n, ViewGroup);
    const mobile = Java.cast(field(n, 'a'), View);
    const wifi = Java.cast(field(n, 'g'), View);
    if (hidden) {
      netSet.call(n, mobile, 8);
      wifi.setVisibility(8);
      // OEM callbacks may set visibility back to VISIBLE. Detached children
      // cannot be rendered, and the remaining icons close the gap naturally.
      if (mobile.getParent()) parent.removeView(mobile);
      if (wifi.getParent()) parent.removeView(wifi);
    } else {
      if (!mobile.getParent()) parent.addView(mobile, 2, mobile.getLayoutParams());
      if (!wifi.getParent()) parent.addView(wifi, 4, wifi.getLayoutParams());
      n.e.overload().call(n);
      netWifi.call(n);
    }
  }
  function applyCurrent() {
    if (control) {
      if (hidden) hideTiles(control);
      else restoreTiles(control);
    }
    if (net) applyTop(net);
    applyApps();
  }
  function queueApply() {
    const ticket = ++generation;
    Java.scheduleOnMainThread(function () {
      if (ticket !== generation) return;
      try { applyCurrent(); } catch (e) { log('apply error: ' + errorText(e)); }
    });
  }

  render.implementation = function (list) {
    const result = render.call(this, list);
    try {
      captureControl(this);
      if (hidden) hideTiles(this);
    } catch (e) { log('render error: ' + errorText(e)); }
    return result;
  };
  const initView = G.initView.overload('android.view.View');
  initView.implementation = function (view) {
    const result = initView.call(this, view);
    try { captureControl(this); } catch (e) { log('init error: ' + e); }
    return result;
  };

  const nfcClick = Q.g.overload();
  nfcClick.implementation = function () {
    try {
      setContext(field(this, 'b'));
      const next = !hidden;
      hidden = next;
      if (!saveMode()) {
        hidden = !next;
        throw new Error('state write failed');
      }
      log('NFC tile toggled hidden=' + hidden);
      queueApply();
    } catch (e) {
      log('NFC tile error: ' + e);
    }
    // Do not call the OEM method: it controls the physical NFC radio.
  };

  netSet.implementation = function (view, visibility) {
    const target = hidden && view && view.getId() === mobileId ? 8 : visibility;
    return netSet.call(this, view, target);
  };
  netWifi.implementation = function () {
    const result = netWifi.call(this);
    if (hidden) {
      try { Java.cast(field(this, 'g'), View).setVisibility(8); }
      catch (e) { log('wifi icon error: ' + e); }
    }
    return result;
  };
  const netAttach = Net.onAttachedToWindow.overload();
  netAttach.implementation = function () {
    const result = netAttach.call(this);
    try {
      captureNet(this);
      if (hidden) applyTop(this);
    } catch (e) { log('net attach error: ' + e); }
    return result;
  };

  // v8: native detail window, built from original-color status snapshots.
  const showDetails = StatusPresenter.a.overload('android.view.View');
  showDetails.implementation = function (view) {
    let bridge = null;
    try { bridge = ensureTheme(); bridge.beginDetails(); }
    catch (e) { log('detail theme prepare error: ' + errorText(e)); }
    try {
      const result = showDetails.call(this, view);
      try {
        if (bridge) {
          const dialog = field(this, 'f');
          if (dialog) { const root = field(dialog, 'a'); if (root) bridge.bindDetails(Java.cast(root, View)); }
        }
      } catch (e) { log('detail theme bind error: ' + errorText(e)); }
      return result;
    } finally { if (bridge) bridge.endDetails(); }
  };

  const collect = StatusPresenter.a.overload('android.view.View', 'java.util.ArrayList');
  collect.implementation = function (view, list) {
    if (hidden && view) {
      try {
        const tag = view.getTag();
        if (tag && (tag.toString() === 'mobile' || tag.toString() === 'wifi')) return;
      } catch (e) { log('status filter error: ' + e); }
    }
    return collect.call(this, view, list);
  };

  // v9: redirect only the QuickCenter Breeno header's launch callback.
  // Keep the OEM click/gesture/animation guards and resolve the installed app.
  try {
    const BreenoHeader = Java.use('e.e.c.b.n.f.la');
    const headerLaunch = BreenoHeader.a.overload('android.os.Bundle');
    headerLaunch.implementation = function (_bundle) {
      try {
        const app = Java.use('android.app.ActivityThread').currentApplication();
        const intent = app.getPackageManager().getLaunchIntentForPackage('com.example.wristai');
        if (!intent) throw new Error('WristAI launcher activity unavailable');
        intent.addFlags(0x10000000);
        app.startActivity(intent);
        log('QuickCenter header opened com.example.wristai');
      } catch (e) {
        log('QuickCenter WristAI launch error: ' + errorText(e));
      }
    };
    log('QuickCenter WristAI header hook ready');
  } catch (e) { log('QuickCenter WristAI hook error: ' + errorText(e)); }

  let matched = 0;
  Java.choose('e.e.c.b.u.h.G', {
    onMatch: function (g) {
      if (matched++) return;
      try { captureControl(g); if (hidden) queueApply(); }
      catch (e) { log('initial control error: ' + e); }
    },
    onComplete: function () { log('control instances=' + matched); }
  });
  let netMatched = 0;
  Java.choose('com.heytap.wearable.launcher.statusbar.view.NetSignalView', {
    onMatch: function (n) {
      if (netMatched++) return;
      try { captureNet(n); if (hidden) queueApply(); }
      catch (e) { log('initial net error: ' + e); }
    },
    onComplete: function () { log('net instances=' + netMatched); }
  });
  let appsMatched = 0;
  Java.choose('com.heytap.wearable.launcher.allapps.view.AllAppsContainerView', {
    onMatch: function (a) {
      if (appsMatched++) return;
      try {
        captureApps(a);
        // The app model may have loaded before this agent was injected.
        // Refresh from the presenter's full installed-app source once.
        Java.scheduleOnMainThread(function () {
          try {
            const presenter = Java.cast(field(apps, 'mPresenter'), AppsPresenter);
            presenter.b.overload().call(presenter);
          }
          catch (e) { log('initial app reload error: ' + errorText(e)); }
        });
      } catch (e) { log('initial apps error: ' + errorText(e)); }
    },
    onComplete: function () { log('app containers=' + appsMatched); }
  });
  log('hooks ready');
  rpc.exports = {
    themestatus: function () { return themeMain(function () {}); },
    themecorners: function (enabled) { return themeMain(function (bridge) { bridge.setCorners(!!enabled); }); },
    thememode: function (value) { return themeMain(function (bridge) { bridge.setMode(String(value)); }); },
    themeseed: function (seed) { return themeMain(function (bridge) { bridge.setSeed(seed | 0); }); },
    themerefresh: function () { return themeMain(function (bridge) { bridge.refresh(); }); },
    status: function () { return { hidden: hidden, control: !!control, net: !!net, apps: !!apps, fullApps: fullApps ? fullApps.size() : null }; },
    setmode: function (value) {
      return new Promise(function (resolve) {
        Java.scheduleOnMainThread(function () {
          try {
            if (!context) throw new Error('context unavailable');
            hidden = !!value;
            if (!saveMode()) throw new Error('state write failed');
            queueApply();
            resolve({ ok: true, hidden: hidden });
          } catch (e) {
            log('setmode error: ' + errorText(e));
            resolve({ ok: false, error: String(e) });
          }
        });
      });
    },
    cleanup: function () {
      return new Promise(function (resolve) {
        Java.scheduleOnMainThread(function () {
          try {
            hidden = false;
            if (context) saveMode();
            applyCurrent();
            log('cleanup restored');
            resolve(true);
          } catch (e) { log('cleanup error: ' + errorText(e)); resolve(false); }
        });
      });
    }
  };
});

// QQ blue point v2: native animated indicator row, Android watchface only.
// No new ART method hooks, Java.registerClass, overlay window or polling.
Java.perform(function () {
  const Thread = Java.use('android.app.ActivityThread');
  const Loader = Java.use('dalvik.system.DexClassLoader');
  const File = Java.use('java.io.File');
  const app = Thread.currentApplication();
  const cache = File.$new(app.getCodeCacheDir(), 'qq-blue-point-v2');
  if (!cache.exists() && !cache.mkdir()) throw new Error('Blue point code cache unavailable');
  const loader = Loader.$new('/data/local/tmp/wearqq-blue-ui/blue-point.jar', cache.getAbsolutePath(), null, app.getClassLoader());
  const Blue = Java.ClassFactory.get(loader).use('dev.codex.wearqq.blue.BluePoint');
  Java.scheduleOnMainThread(function () { Blue.start(app); });
  function onMain(fn) { return new Promise(resolve => Java.scheduleOnMainThread(function () { fn(); resolve(JSON.parse(String(Blue.status()))); })); }
  Object.assign(rpc.exports, {
    bluestatus: function () { return onMain(function () {}); },
    bluepreview: function (value) { return onMain(function () { Blue.preview(!!value); }); },
    bluetesttaps: function (gap) { return onMain(function () { Blue.testTaps(gap); }); },
    bluecleanup: function () { return onMain(function () { Blue.cleanup(); }); }
  });
});


// OPPO Watch2 OW20W1_11_A.92 only. Also appended to the shared Launcher Hook v11.
// Adds only OWOrnate; the existing supervisor applies it on each Launcher startup.
Java.perform(function () {
  const Log = Java.use('android.util.Log');
  try {
    const Registry = Java.use('e.e.c.b.n.b');
    const registry = Registry.a.overload().call(Registry);
    const field = registry.getClass().getDeclaredField('a');
    field.setAccessible(true);
    const list = Java.cast(field.get(registry), Java.use('java.util.List'));
    const pkg = Java.use('java.lang.String').$new('dev.codex.watchworkshop');
    if (!list.contains(pkg)) list.add(pkg);
    const allowed = Registry.a.overload('java.lang.String').call(registry, pkg);
    if (!allowed) throw new Error('OWOrnate package was not admitted');
    Log.i('OWOrnateWidgetHost', 'v11 allowlist ready');
  } catch (error) {
    Log.e('OWOrnateWidgetHost', String(error));
  }
});


// v11: hide Breeno on the negative screen (QuickCenter).
// When Settings.System 'codex_qc_hide_breeno' = 1, the Breeno row's itemView is
// collapsed (height 0, GONE, and the list's per-item bottom spacing cancelled),
// so the next item moves to the top. Breeno stays bound: every native access to
// the Breeno holder (ra.r() on each exit reads it unguarded) keeps working, and
// the adapter's item count / view type / binding stay 100% native.
// It is applied on the main thread once for the QuickCenterView that already
// exists at injection, then on each QuickCenterView.onViewStartShowing /
// onViewAllShow (twice per open, never per frame). The Breeno row is found by
// its resource id and View.getParent(); no obfuscated launcher member is used.
// The native bind never resets the row's size or visibility, so the collapse
// survives rebinds.
Java.perform(function () {
  const Log = Java.use('android.util.Log');
  function qlog(m) { try { Log.i('QuickCenterPlus', String(m)); } catch (_) {} }
  function qerr(e) { qlog('hide-breeno error: ' + e + ' @ ' + (e && e.stack ? String(e.stack).split('\n').slice(0, 3).join(' | ') : '')); }
  const LPKG = 'com.heytap.wearable.launcher';
  const KEY = 'codex_qc_hide_breeno';
  try {
    const QCV = Java.use('com.heytap.wearable.launcher.quickcenter.view.QuickCenterView');
    const View = Java.use('android.view.View');
    const MLP = Java.use('android.view.ViewGroup$MarginLayoutParams');
    const Settings = Java.use('android.provider.Settings$System');
    const getInt = Settings.getInt.overload('android.content.ContentResolver', 'java.lang.String', 'int');
    let orig = null; // the Breeno row's own size/visibility, saved before the first collapse

    function wanted(ctx) {
      try { return getInt.call(Settings, ctx.getContentResolver(), KEY, 0) === 1; } catch (e) { return false; }
    }

    function apply(qcv, why) {
      const res = qcv.getResources();
      const listId = res.getIdentifier('quick_list', 'id', LPKG);
      const bgId = res.getIdentifier('breeno_bg', 'id', LPKG);
      if (!listId || !bgId) { qlog('breeno ids missing'); return; }
      const listRaw = qcv.findViewById(listId);
      if (listRaw === null) return;
      const list = Java.cast(listRaw, View); // findViewById comes back untyped; cast before use
      const bgRaw = list.findViewById.overload('int').call(list, bgId);
      if (bgRaw === null) return; // Breeno row is not attached yet; the next show retries
      let row = Java.cast(bgRaw, View);
      let parent = row.getParent();
      for (let depth = 0; parent !== null && !list.equals(parent); depth++) {
        if (depth > 8) return;
        row = Java.cast(parent, View);
        parent = row.getParent();
      }
      if (parent === null) return;
      const lp = Java.cast(row.getLayoutParams(), MLP);
      if (wanted(qcv.getContext())) {
        if (lp.height.value === 0 && row.getVisibility() === 8) return;
        if (orig === null) orig = { h: lp.height.value, bm: lp.bottomMargin.value, vis: row.getVisibility() };
        let pad = 0;
        const padId = res.getIdentifier('quick_center_card_vertical_padding', 'dimen', LPKG);
        if (padId) pad = Math.floor(res.getDimension(padId));
        lp.height.value = 0;
        lp.bottomMargin.value = orig.bm - pad; // the list decoration adds `pad` below every item
        row.setLayoutParams(lp);
        row.setVisibility(8); // GONE
        qlog('breeno hidden (' + why + ')');
      } else if (orig !== null) {
        lp.height.value = orig.h;
        lp.bottomMargin.value = orig.bm;
        row.setLayoutParams(lp);
        row.setVisibility(orig.vis);
        orig = null;
        qlog('breeno shown (' + why + ')');
      }
    }

    function hookShow(name) {
      const m = QCV[name].overload();
      m.implementation = function () {
        m.call(this);
        try { apply(this, name); } catch (e) { qerr(e); }
      };
    }
    hookShow('onViewStartShowing');
    hookShow('onViewAllShow');

    let seen = 0;
    Java.choose('com.heytap.wearable.launcher.quickcenter.view.QuickCenterView', {
      onMatch: function (v) {
        if (seen++) return;
        const kept = Java.retain(v);
        Java.scheduleOnMainThread(function () {
          try { apply(kept, 'attach'); } catch (e) { qerr(e); }
        });
      },
      onComplete: function () { qlog('existing QuickCenterView=' + seen); }
    });
    qlog('hide-breeno ready');
  } catch (e) { qlog('hide-breeno unavailable: ' + e); }
});


// v12 append block: native screenshot/record tiles on the second status page.
// Appended after v11. The render wrapper calls v11's existing implementation
// before adding the two fixed controls. No native ordering or MCU entries.
Java.perform(function () {
  const TAG = 'OWCaptureTiles';
  const APP = 'dev.codex.watchworkshop';
  const ACTIVITY = 'dev.codex.watchworkshop.CaptureEntryActivity';
  const LAUNCHER = 'com.heytap.wearable.launcher';
  const PHASE_KEY = 'codex_capture_phase';
  const ENABLED_KEY = 'codex_capture_enabled';
  const SCREENSHOT_ID = 10041;
  const RECORD_ID = 10042;

  function log(message) {
    try { Java.use('android.util.Log').i(TAG, String(message)); } catch (_) {}
  }

  try {
    const Context = Java.use('android.content.Context');
    const View = Java.use('android.view.View');
    const ViewGroup = Java.use('android.view.ViewGroup');
    const Intent = Java.use('android.content.Intent');
    const ComponentName = Java.use('android.content.ComponentName');
    const PackageManager = Java.use('android.content.pm.PackageManager');
    const Settings = Java.use('android.provider.Settings$System');
    const ContentObserver = Java.use('android.database.ContentObserver');
    const Handler = Java.use('android.os.Handler');
    const Looper = Java.use('android.os.Looper');
    const Integer = Java.use('java.lang.Integer');
    const ArrayList = Java.use('java.util.ArrayList');
    const ActivityThread = Java.use('android.app.ActivityThread');
    const L = Java.use('e.e.c.b.u.f.l');
    const TileContract = Java.use('e.e.c.b.u.f.h');
    const Status = Java.use('e.e.c.b.u.b.c');
    const Toggle = Java.use('com.heytap.wearable.launcher.statusbar.view.LauncherToggleButton');
    const OnClickListener = Java.use('android.view.View$OnClickListener');
    const Page = Java.use('e.e.c.b.u.h.H');
    const Panel = Java.use('com.heytap.wearable.launcher.statusbar.view.StatusPanelView');
    const Windows = Java.use('e.e.c.b.A.a.f');

    let cachedTheme = null;
    let currentPanel = null;
    let pendingAction = null;
    let pendingTicket = 0;
    let launchScheduled = false;
    let phaseObserver = null;
    let observedEnabled = false;
    let phaseResolver = null;

    function field(object, name) {
      let cls = object.getClass();
      while (cls) {
        try {
          const found = cls.getDeclaredField(name);
          found.setAccessible(true);
          return found.get(object);
        } catch (_) {
          cls = cls.getSuperclass();
        }
      }
      throw new Error('missing field ' + name);
    }

    function setField(object, name, value) {
      let cls = object.getClass();
      while (cls) {
        try {
          const found = cls.getDeclaredField(name);
          found.setAccessible(true);
          found.set(object, value);
          return;
        } catch (error) {
          cls = cls.getSuperclass();
        }
      }
      throw new Error('missing field ' + name);
    }

    function setIntField(object, name, value) {
      let cls = object.getClass();
      while (cls) {
        try {
          const found = cls.getDeclaredField(name);
          found.setAccessible(true);
          found.setInt(object, value);
          return;
        } catch (error) {
          cls = cls.getSuperclass();
        }
      }
      throw new Error('missing int field ' + name);
    }

    function tileId(tile) {
      return Java.cast(field(tile, 'a'), Integer).intValue();
    }

    function readPhaseName(context) {
      try {
        const value = Settings.getString.overload(
          'android.content.ContentResolver', 'java.lang.String'
        ).call(Settings, context.getContentResolver(), PHASE_KEY);
        return value === null ? 'idle' : String(value);
      } catch (error) {
        log('phase read failed: ' + error);
        return 'idle';
      }
    }

    function moduleEnabled(context) {
      return Settings.getInt.overload('android.content.ContentResolver','java.lang.String','int')
        .call(Settings,context.getContentResolver(),ENABLED_KEY,0) === 1;
    }

    function captureApp(context) {
      try {
        const pm = Java.cast(context.getPackageManager(), PackageManager);
        const info = pm.getPackageInfo.overload('java.lang.String', 'int').call(pm, APP, 0);
        const rawVersion = info.versionCode;
        const versionCode = rawVersion === null ? 0 :
          (typeof rawVersion === 'number' ? rawVersion : Number(rawVersion.value));
        if (versionCode < 17) {
          log('capture app version ' + versionCode + ' is below 17');
          return null;
        }
        const intent = Intent.$new();
        intent.setComponent(ComponentName.$new(APP, ACTIVITY));
        const resolved = pm.resolveActivity.overload('android.content.Intent', 'int')
          .call(pm, intent, 0);
        if (resolved === null) {
          log('CaptureEntryActivity does not resolve');
          return null;
        }
        return pm.getResourcesForApplication.overload('java.lang.String').call(pm, APP);
      } catch (error) {
        log('capture app gate: ' + error);
        return null;
      }
    }

    function appIcon(resources, name) {
      const id = resources.getIdentifier(name, 'drawable', APP);
      if (!id) throw new Error('missing drawable ' + name);
      return resources.getDrawable.overload('int').call(resources, id);
    }

    function launchCapture(action, ticket) {
      if (ticket !== pendingTicket || pendingAction !== action) return;
      const wanted = action;
      pendingAction = null;
      launchScheduled = false;
      try {
        const app = ActivityThread.currentApplication();
        const intent = Intent.$new();
        intent.setComponent(ComponentName.$new(APP, ACTIVITY));
        intent.putExtra.overload('java.lang.String','java.lang.String').call(intent,'action',wanted);
        // NEW_TASK | EXCLUDE_FROM_RECENTS | NO_ANIMATION
        intent.addFlags(0x10000000 | 0x00200000 | 0x00010000);
        app.startActivity(intent);
        log('opened CaptureEntryActivity action=' + wanted);
      } catch (error) {
        log('capture launch failed: ' + error);
      }
    }

    function scheduleGoneLaunch() {
      if (pendingAction === null || launchScheduled) return;
      launchScheduled = true;
      const action = pendingAction;
      const ticket = pendingTicket;
      setTimeout(function () {
        if (ticket !== pendingTicket || pendingAction !== action) return;
        Java.scheduleOnMainThread(function () {
          launchCapture(action, ticket);
        });
      }, 160);
    }

    function requestCapture(action) {
      if (pendingAction !== null) {
        log('capture request already pending');
        return;
      }
      pendingAction = action;
      launchScheduled = false;
      const ticket = ++pendingTicket;
      setTimeout(function () {
        if (ticket !== pendingTicket || pendingAction !== action || launchScheduled) return;
        pendingAction = null;
        log('capture panel close timed out; no capture started');
        Java.scheduleOnMainThread(function () {
          try { if (currentPanel !== null) Java.use('android.widget.Toast')
            .makeText(currentPanel.getContext(), '控制中心收起未完成，请重试', 0).show(); } catch (_) {}
        });
      }, 650);
      try {
        const windows = Windows.e.overload().call(Windows);
        Windows.a.overload('java.lang.String').call(windows, 'capture');
        log('hide requested for ' + action);
      } catch (error) {
        log('hide request failed: ' + error);
      }
    }

    const LongPress = Java.registerClass({
      name: 'dev.codex.owornate.CaptureLongPress',
      implements: [Java.use('android.view.View$OnLongClickListener')],
      fields: { captureAction: 'java.lang.String' },
      methods: {
        $init: {
          returnType: 'void', argumentTypes: ['java.lang.String'],
          implementation: function (action) { this.$super.$init();this.captureAction.value=action; }
        },
        onLongClick: function (_view) {
          requestCapture(String(this.captureAction.value)+'-settings');
          return true;
        }
      }
    });

    const StopDrawable = Java.registerClass({
      name: 'dev.codex.owornate.CaptureStopDrawable',
      superClass: Java.use('android.graphics.drawable.Drawable'),
      fields: {
        stopPaint: 'android.graphics.Paint',
        stopSize: 'int'
      },
      methods: {
        $init: {
          returnType: 'void',
          argumentTypes: ['int'],
          implementation: function (size) {
            this.$super.$init();
            this.stopSize.value = size;
            const paint = Java.use('android.graphics.Paint').$new(1);
            paint.setColor(0xFFFF4D4F | 0);
            this.stopPaint.value = paint;
          }
        },
        draw: {
          returnType: 'void',
          argumentTypes: ['android.graphics.Canvas'],
          implementation: function (canvas) {
            const bounds = this.getBounds();
            const inset = Math.min(bounds.width(), bounds.height()) * 0.25;
            canvas.drawRect(bounds.left.value + inset, bounds.top.value + inset,
              bounds.right.value - inset, bounds.bottom.value - inset, this.stopPaint.value);
          }
        },
        setAlpha: {
          returnType: 'void',
          argumentTypes: ['int'],
          implementation: function (alpha) {
            this.stopPaint.value.setAlpha(alpha);
            this.invalidateSelf();
          }
        },
        setColorFilter: [
          {
            returnType: 'void',
            argumentTypes: ['android.graphics.ColorFilter'],
            implementation: function (_filter) {}
          },
          {
            returnType: 'void',
            argumentTypes: ['int', 'android.graphics.PorterDuff$Mode'],
            implementation: function (_color, _mode) {}
          }
        ],
        getOpacity: {
          returnType: 'int',
          argumentTypes: [],
          implementation: function () { return -3; }
        },
        getIntrinsicWidth: {
          returnType: 'int',
          argumentTypes: [],
          implementation: function () { return this.stopSize.value; }
        },
        getIntrinsicHeight: {
          returnType: 'int',
          argumentTypes: [],
          implementation: function () { return this.stopSize.value; }
        }
      }
    });

    const PhaseObserver = Java.registerClass({
      name: 'dev.codex.owornate.CapturePhaseObserver',
      superClass: ContentObserver,
      methods: {
        $init: {
          returnType: 'void',
          argumentTypes: ['android.os.Handler'],
          implementation: function (handler) {
            this.$super.$init(handler);
          }
        },
        onChange: {
          returnType: 'void',
          argumentTypes: ['boolean'],
          implementation: function (_selfChange) {
            if (phaseObserver === null) return;
            const enabled = moduleEnabled(currentPanel.getContext());
            if (enabled !== observedEnabled) {
              observedEnabled = enabled;
              appendControls(currentPanel);
            } else refreshVisibleControllers();
          }
        }
      }
    });

    function controllerContext(controller) {
      return Java.cast(field(controller, 'b'), Context);
    }

    const Controller = Java.registerClass({
      name: 'dev.codex.owornate.CaptureTileController',
      superClass: L,
      implements: [TileContract, OnClickListener],
      fields: {
        captureAction: 'java.lang.String',
        captureLayout: 'int',
        captureButton: 'com.heytap.wearable.launcher.statusbar.view.LauncherToggleButton',
        captureResources: 'android.content.res.Resources'
      },
      methods: {
        $init: {
          returnType: 'void',
          argumentTypes: [
            'android.content.Context',
            'int',
            'java.lang.String',
            'android.content.res.Resources'
          ],
          implementation: function (context, id, action, resources) {
            this.$super.$init(context);
            this.captureAction.value = action;
            this.captureResources.value = resources;
            setIntField(this, 'a', id);

            const layout = context.getResources().getIdentifier('tile_toggle_setting', 'layout', LAUNCHER);
            const image = context.getResources().getIdentifier('tile_image', 'id', LAUNCHER);
            if (!layout || !image) throw new Error('native tile resources missing');
            this.captureLayout.value = layout;

            const inflater = Java.use('android.view.LayoutInflater').from(context);
            const content = inflater.inflate(layout, null, false);
            const button = Java.cast(content.findViewById(image), Toggle);
            setField(this, 'd', content);
            this.captureButton.value = button;
            button.setClickable(true);
            button.setOnClickListener(this);
            button.setOnLongClickListener(LongPress.$new(action));
            this.refreshState();
          }
        },

        refreshState: function () {
          const button = this.captureButton.value;
          if (button === null || button === undefined) return;
          const action = String(this.captureAction.value);
          const phase = readPhaseName(controllerContext(this));
          let icon = 'ic_capture_screenshot';
          if (action === 'record') {
            const active = phase === 'starting' || phase === 'recording' || phase === 'stopping';
            icon = active ? 'ic_capture_stop' : 'ic_capture_record';
          }
          const active = action === 'record' && (phase === 'starting' || phase === 'recording' || phase === 'stopping');
          const drawable = active
            ? StopDrawable.$new(Math.round(24 * button.getResources().getDisplayMetrics().density.value))
            : appIcon(this.captureResources.value, icon);
          button.setIconDrawable(drawable);
          button.setContentDescription((action === 'screenshot' ? '截屏' :
            phase === 'stopping' ? '正在保存录屏，点按重试停止' : active ? '停止录屏' : '录屏')+'，长按打开 OWOrnate 设置');
          button.setChecked(false);
          // A stale stopping projection must still allow a stop retry or long press.
          button.setEnableState(true);
          button.setEnabled(true);
        },

        performCapture: function () {
          const action = String(this.captureAction.value);
          requestCapture(action);
        },

        onClick: function (_view) { this.performCapture(); },
        f: function () { return true; },
        g: function () { this.performCapture(); },
        getContentView: function () { return this.captureButton.value; },
        o: [
          {
            returnType: 'void',
            argumentTypes: [],
            implementation: function () { this.refreshState(); }
          },
          {
            returnType: 'void',
            argumentTypes: ['boolean'],
            // The native host calls o(boolean) on its Rx worker, then v() on main.
            implementation: function (_refresh) {}
          }
        ],
        a: [
          {
            returnType: 'boolean',
            argumentTypes: ['int', 'long'],
            implementation: function (_status, _data) { return false; }
          },
          {
            returnType: 'void',
            argumentTypes: ['int', 'int'],
            implementation: function (_status, _data) {}
          },
          {
            returnType: 'void',
            argumentTypes: ['int', 'long', 'int'],
            implementation: function (_status, _data, _result) {}
          }
        ],
        k: {
          returnType: 'int',
          argumentTypes: [],
          implementation: function () { return this.captureLayout.value; }
        },
        m: {
          returnType: 'java.lang.String',
          argumentTypes: [],
          implementation: function () { return this.captureAction.value; }
        },
        q: {
          returnType: 'java.lang.String',
          argumentTypes: [],
          implementation: function () { return ''; }
        },
        r: {
          returnType: 'java.lang.String',
          argumentTypes: [],
          implementation: function () { return ''; }
        },
        t: {
          returnType: 'e.e.c.b.u.b.c',
          argumentTypes: [],
          implementation: function () { return Status.$new(); }
        },
        v: {
          returnType: 'void',
          argumentTypes: [],
          implementation: function () { this.refreshState(); }
        },
        i: {
          returnType: 'void',
          argumentTypes: ['int'],
          implementation: function (_position) {}
        },
        j: {
          returnType: 'void',
          argumentTypes: ['int'],
          implementation: function (_position) {}
        },
        onViewAttachedToWindow: {
          returnType: 'void',
          argumentTypes: ['android.view.View'],
          implementation: function (_view) {}
        },
        onViewDetachedFromWindow: {
          returnType: 'void',
          argumentTypes: ['android.view.View'],
          implementation: function (_view) {}
        }
      }
    });

    function themeClassFromView(view) {
      try {
        const name = String(view.getClass().getName());
        if (name === 'com.heytap.wearable.launcher.statusbar.view.LauncherToggleButton') {
          const icon = Java.cast(view, Toggle).getIconDrawable();
          if (icon !== null) {
            const iconClass = icon.getClass();
            const iconName = String(iconClass.getName());
            if (iconName.indexOf('dev.codex.watchtheme.') === 0) {
              const loader = iconClass.getClassLoader();
              if (loader !== null) {
                return Java.ClassFactory.get(loader).use('dev.codex.watchtheme.ControlTheme');
              }
            }
          }
        }
        if (ViewGroup.class.isInstance(view)) {
          const group = Java.cast(view, ViewGroup);
          for (let i = 0; i < group.getChildCount(); i++) {
            const found = themeClassFromView(group.getChildAt(i));
            if (found !== null) return found;
          }
        }
      } catch (_) {}
      return null;
    }

    function rebindTheme(panel) {
      try {
        const id = panel.getResources().getIdentifier('status_bar', 'id', LAUNCHER);
        if (!id) return;
        const raw = panel.findViewById.overload('int').call(panel, id);
        if (raw === null) return;
        const root = Java.cast(raw, View);
        if (cachedTheme === null) cachedTheme = themeClassFromView(root);
        if (cachedTheme === null) {
          try { cachedTheme = Java.use('dev.codex.watchtheme.ControlTheme'); } catch (_) {}
        }
        if (cachedTheme !== null) {
          cachedTheme.bind(root);
          log('theme rebound after append');
          return;
        }
        try { rpc.exports.themerefresh(); } catch (_) {}
      } catch (error) {
        log('theme rebind failed: ' + error);
      }
    }

    function findController(list, id) {
      for (let i = 0; i < list.size(); i++) {
        const tile = list.get(i);
        try { if (tileId(tile) === id) return Java.cast(tile, Controller); } catch (_) {}
      }
      return null;
    }

    function ensureController(page, list, context, id, action, resources) {
      let tile = findController(list, id);
      if (tile === null) {
        tile = Controller.$new(context, id, action, resources);
        list.add(tile);
        page.addView(Java.cast(field(tile, 'd'), View));
      } else {
        const content = Java.cast(field(tile, 'd'), View);
        if (content.getParent() === null) page.addView(content);
      }
      try { tile.refreshState(); } catch (_) {}
    }

    function refreshVisibleControllers() {
      if (phaseObserver === null || currentPanel === null) return;
      try {
        const control = field(currentPanel, 'b');
        if (control === null) return;
        const pages = Java.cast(field(control, 'd'), ArrayList);
        if (pages === null || pages.size() < 2) return;
        const page = Java.cast(pages.get(1), Page);
        if (String(page.getClass().getName()) !== 'e.e.c.b.u.h.J') return;
        const list = Java.cast(field(page, 'h'), ArrayList);
        const screenshot = findController(list, SCREENSHOT_ID);
        const record = findController(list, RECORD_ID);
        if (screenshot !== null) screenshot.refreshState();
        if (record !== null) record.refreshState();
      } catch (error) {
        log('phase refresh: ' + error);
      }
    }

    function registerPhaseObserver(panel) {
      if (phaseObserver !== null) return;
      try {
        const handler = Handler.$new(Looper.getMainLooper());
        const observer = PhaseObserver.$new(handler);
        const context = Java.cast(panel.getContext(), Context);
        const resolver = Java.retain(context.getContentResolver());
        const uri = Settings.getUriFor.overload('java.lang.String').call(Settings, PHASE_KEY);
        resolver.registerContentObserver.overload(
          'android.net.Uri', 'boolean', 'android.database.ContentObserver'
        ).call(resolver, uri, false, observer);
        resolver.registerContentObserver.overload('android.net.Uri','boolean','android.database.ContentObserver')
          .call(resolver,Settings.getUriFor(ENABLED_KEY),false,observer);
        observedEnabled = moduleEnabled(context);
        phaseResolver = resolver;
        phaseObserver = observer;
        log('phase observer registered');
      } catch (error) {
        log('phase observer registration failed: ' + error);
      }
    }

    function unregisterPhaseObserver() {
      if (phaseObserver === null) return;
      const observer = phaseObserver;
      const resolver = phaseResolver;
      phaseObserver = null;
      phaseResolver = null;
      try {
        resolver.unregisterContentObserver.overload('android.database.ContentObserver')
          .call(resolver, observer);
        log('phase observer unregistered');
      } catch (error) {
        log('phase observer unregister failed: ' + error);
      }
    }

    function appendControls(panel) {
      try {
        try {
          if (currentPanel === null || !currentPanel.equals(panel)) {
            currentPanel = Java.retain(panel);
          }
        } catch (_) {
          currentPanel = Java.retain(panel);
        }
        const control = field(panel, 'b');
        if (control === null) return;
        const pages = Java.cast(field(control, 'd'), ArrayList);
        if (pages === null || pages.size() < 2) return;
        const page = Java.cast(pages.get(1), Page);
        if (String(page.getClass().getName()) !== 'e.e.c.b.u.h.J') return;
        const context = Java.cast(panel.getContext(), Context);
        const resources = captureApp(context);
        if (resources === null) return;
        const list = Java.cast(field(page, 'h'), ArrayList);
        if (!moduleEnabled(context)) {
          for (let i=list.size()-1;i>=0;i--) {
            const tile=list.get(i), id=tileId(tile);
            if (id===SCREENSHOT_ID||id===RECORD_ID) {
              page.removeView(Java.cast(field(tile,'d'),View));
              list.remove(i);
            }
          }
          page.requestLayout();
          rebindTheme(panel);
          return;
        }
        ensureController(page, list, context, SCREENSHOT_ID, 'screenshot', resources);
        ensureController(page, list, context, RECORD_ID, 'record', resources);
        page.requestLayout();
        page.invalidate();
        rebindTheme(panel);
      } catch (error) {
        log('append failed: ' + error);
      }
    }

    const render = Java.use('e.e.c.b.u.h.G').a.overload('java.util.concurrent.CopyOnWriteArrayList');
    const previousRender = render.implementation;
    render.implementation = function (list) {
      const result = typeof previousRender === 'function'
        ? previousRender.apply(this, arguments)
        : render.call(this, list);
      try {
        if (currentPanel !== null && this.equals(field(currentPanel, 'b'))) {
          appendControls(currentPanel);
        }
      } catch (error) {
        log('render append: ' + error);
      }
      return result;
    };

    // v11's restoreTiles uses render.call directly, bypassing G.a implementations.
    // Run after its queued NFC visibility update so these two controls survive it.
    const nfc = Java.use('e.e.c.b.u.f.a.Q').g.overload();
    const previousNfc = nfc.implementation;
    if (typeof previousNfc === 'function') {
      nfc.implementation = function () {
        const result = previousNfc.apply(this, arguments);
        Java.scheduleOnMainThread(function () {
          if (currentPanel !== null) appendControls(currentPanel);
        });
        return result;
      };
    }

    function hookPanel(name, callback) {
      const original = Panel[name].overload();
      original.implementation = function () {
        const result = original.call(this);
        try { callback(this); } catch (error) { log(name + ' callback: ' + error); }
        return result;
      };
    }

    hookPanel('onViewStartShowing', function (panel) { appendControls(panel); });
    hookPanel('onViewAllShow', function (panel) {
      appendControls(panel);
      registerPhaseObserver(panel);
    });
    hookPanel('onViewGone', function (_panel) {
      unregisterPhaseObserver();
      scheduleGoneLaunch();
    });
    log('capture controls ready');
  } catch (error) {
    log('capture controls unavailable: ' + error);
  }
});

