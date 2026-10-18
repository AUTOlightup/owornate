
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

