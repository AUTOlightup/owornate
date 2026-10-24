// v13: an event-driven recording indicator in the Launcher's native hint row.
// Shares only the row with QQ; never changes native hints, their alpha or visibility.
Java.perform(function () {
  const TAG = 'OWCaptureIndicator';
  function log(message) {
    try { Java.use('android.util.Log').i(TAG, String(message)); } catch (_) {}
  }
  try {
    const ActivityThread = Java.use('android.app.ActivityThread');
    const Activity = Java.use('android.app.Activity');
    const LinearLayout = Java.use('android.widget.LinearLayout');
    const LayoutParams = Java.use('android.widget.LinearLayout$LayoutParams');
    const CircleImage = Java.use('com.heytap.wearable.launcher.views.CircleImageView2');
    const Settings = Java.use('android.provider.Settings$System');
    const ContentObserver = Java.use('android.database.ContentObserver');
    const PHASE = 'codex_capture_phase', ENABLED = 'codex_capture_enabled';
    const TOP_ROW = 2131296470, APP_ICON = 2131296463;
    let decor = null, row = null, icon = null, resolver = null, observer = null;

    // $isSameObject is a Frida wrapper primitive present on every wrapper (concrete or
    // interface); .equals() on an interface-typed wrapper (e.g. View.getParent()) can be
    // unresolved on this launcher's bridge. Identity is also the correct semantics here.
    function same(a, b) { return a !== null && b !== null && a.$isSameObject(b); }
    function field(object, name) {
      let cls = object.getClass();
      while (cls !== null) {
        try {
          const found = cls.getDeclaredField(name);
          found.setAccessible(true);
          return found.get(object);
        } catch (_) { cls = cls.getSuperclass(); }
      }
      throw new Error('missing field ' + name);
    }
    function render() {
      if (icon === null || resolver === null) return;
      try {
        const phase = String(Settings.getString.overload('android.content.ContentResolver', 'java.lang.String')
          .call(Settings, resolver, PHASE));
        const enabled = Settings.getInt.overload('android.content.ContentResolver', 'java.lang.String', 'int')
          .call(Settings, resolver, ENABLED, 0) === 1;
        // Starting has no confirmed native recorder yet. Stopping is still saving this session.
        const visibility = enabled && (phase === 'recording' || phase === 'stopping') ? 0 : 8;
        if (icon.getVisibility() !== visibility) {
          icon.setVisibility(visibility);
          log(visibility === 0 ? 'visible phase=' + phase : 'hidden phase=' + phase);
        }
      } catch (error) {
        icon.setVisibility(8);
        log('state read failed: ' + error);
      }
    }
    const PhaseObserver = Java.registerClass({
      name: 'dev.codex.owornate.CaptureIndicatorObserver',
      superClass: ContentObserver,
      methods: {
        $init: { returnType: 'void', argumentTypes: ['android.os.Handler'],
          implementation: function (handler) { this.$super.$init(handler); } },
        onChange: { returnType: 'void', argumentTypes: ['boolean'],
          implementation: function (_selfChange) { render(); } }
      }
    });
    function detachRow() {
      if (observer !== null && resolver !== null) {
        try { resolver.unregisterContentObserver(observer); } catch (error) { log('unregister: ' + error); }
      }
      observer = null; resolver = null;
      if (icon !== null && row !== null) {
        try { row.removeView(icon); } catch (error) { log('detach: ' + error); }
      }
      icon = null; row = null;
    }
    function attach() {
      if (decor === null) return;
      const found = decor.findViewById(TOP_ROW);
      // `.class.isInstance(...)` throws TypeError:not a function on this launcher's Java
      // bridge; a guarded Java.cast is the portable instanceof test (and gives us the row).
      let foundRow = null;
      if (found !== null) { try { foundRow = Java.cast(found, LinearLayout); } catch (_) { foundRow = null; } }
      if (foundRow === null) { detachRow(); return; }
      if (same(row, found) && icon !== null && same(icon.getParent(), row)) return;
      detachRow();
      row = Java.retain(foundRow);
      const context = row.getContext();
      const nativeIcon = row.findViewById(APP_ICON);
      let params;
      let nativeParams = null;
      if (nativeIcon !== null) {
        try { nativeParams = Java.cast(nativeIcon.getLayoutParams(), LayoutParams); } catch (_) { nativeParams = null; }
      }
      if (nativeParams !== null) {
        params = LayoutParams.$new.overload('android.widget.LinearLayout$LayoutParams')
          .call(LayoutParams, nativeParams);
      } else {
        const resources = row.getResources();
        function dimension(name) {
          const id = resources.getIdentifier(name, 'dimen', 'com.heytap.wearable.launcher');
          if (id === 0) throw new Error('missing native dimension ' + name);
          return resources.getDimensionPixelSize(id);
        }
        params = LayoutParams.$new(dimension('hint_status_width'), dimension('hint_status_height'));
        const margin = dimension('hint_margin');
        params.setMarginStart(margin); params.setMarginEnd(margin);
      }
      const image = CircleImage.$new(context);
      const drawable = Java.use('android.graphics.drawable.GradientDrawable').$new();
      drawable.setShape(1); // OVAL: red recording dot with a white outline at native 16dp size.
      drawable.setColor(0xFFFF4D4F | 0);
      const density = Number(row.getResources().getDisplayMetrics().density.value);
      drawable.setStroke(Math.max(1, Math.round(density)), 0xFFFFFFFF | 0);
      image.setImageDrawable(drawable);
      image.setScaleType(Java.use('android.widget.ImageView$ScaleType').FIT_XY.value);
      // Frida won't coerce a bare JS string to the CharSequence overload on this
      // launcher's View; wrap it explicitly. Decorative only (a11y label).
      image.setContentDescription.overload('java.lang.CharSequence')
        .call(image, Java.use('java.lang.String').$new('正在录屏'));
      image.setFocusable(false); image.setClickable(false); image.setVisibility(8);
      icon = Java.retain(image);
      // Append without changing the positions or touch handling of QQ or native hints.
      row.addView(icon, params);
      resolver = Java.retain(context.getContentResolver());
      observer = Java.retain(PhaseObserver.$new(Java.use('android.os.Handler').$new(
        Java.use('android.os.Looper').getMainLooper())));
      resolver.registerContentObserver.overload('android.net.Uri', 'boolean', 'android.database.ContentObserver')
        .call(resolver, Settings.getUriFor(PHASE), false, observer);
      resolver.registerContentObserver.overload('android.net.Uri', 'boolean', 'android.database.ContentObserver')
        .call(resolver, Settings.getUriFor(ENABLED), false, observer);
      render();
      log('attached native hint row');
    }
    const LayoutListener = Java.registerClass({
      name: 'dev.codex.owornate.CaptureIndicatorLayout',
      implements: [Java.use('android.view.ViewTreeObserver$OnGlobalLayoutListener')],
      methods: { onGlobalLayout: function () {
        try { attach(); } catch (error) { log('layout: ' + error); }
      } }
    });
    const layouts = LayoutListener.$new();
    function unwatch() {
      if (decor !== null) {
        try {
          const tree = decor.getViewTreeObserver();
          if (tree.isAlive()) tree.removeOnGlobalLayoutListener(layouts);
        } catch (error) { log('unwatch: ' + error); }
      }
      detachRow(); decor = null;
    }
    function watch(activity) {
      try {
        const window = activity.getWindow();
        if (window === null) return;
        const next = window.getDecorView();
        if (!same(decor, next)) {
          unwatch();
          decor = Java.retain(next);
          decor.getViewTreeObserver().addOnGlobalLayoutListener(layouts);
        }
        // The watch-face row may be inflated after the Activity resumes.
        attach();
      } catch (error) { log('watch: ' + error); }
    }
    const Lifecycle = Java.registerClass({
      name: 'dev.codex.owornate.CaptureIndicatorLifecycle',
      implements: [Java.use('android.app.Application$ActivityLifecycleCallbacks')],
      methods: {
        onActivityCreated: function (_activity, _state) {},
        onActivityStarted: function (activity) { watch(activity); },
        onActivityResumed: function (activity) { watch(activity); },
        onActivityPaused: function (_activity) {},
        onActivityStopped: function (_activity) {},
        onActivitySaveInstanceState: function (_activity, _state) {},
        onActivityDestroyed: function (activity) {
          const window = activity.getWindow();
          if (window !== null && same(decor, window.getDecorView())) unwatch();
        }
      }
    });
    Java.scheduleOnMainThread(function () {
      try {
        ActivityThread.currentApplication().registerActivityLifecycleCallbacks(Lifecycle.$new());
        const thread = ActivityThread.currentActivityThread();
        const activities = Java.cast(field(thread, 'mActivities'), Java.use('java.util.Map'));
        const records = activities.values().iterator();
        while (records.hasNext()) watch(Java.cast(field(records.next(), 'activity'), Activity));
        log('native indicator ready');
      } catch (error) { log('startup: ' + error); }
    });
  } catch (error) { log('indicator unavailable: ' + error); }
});
