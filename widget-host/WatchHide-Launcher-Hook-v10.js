// OPPO Watch 2 A.92 Launcher: change the real view tree, not a screen overlay.
// No Java.registerClass, heap scan on tap, global View hook, or radio changes.
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


// OPPO Watch2 OW20W1_11_A.92 only. Also appended to the shared Launcher Hook v10.
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
    Log.i('OWOrnateWidgetHost', 'v10 allowlist ready');
  } catch (error) {
    Log.e('OWOrnateWidgetHost', String(error));
  }
});
