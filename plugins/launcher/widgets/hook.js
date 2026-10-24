// WatchWorkshop Launcher widget-host gates.
// OPPO Watch2 OW20W1_11_A.92 only. This module contains no NFC, QQ or WatchHide code.
// Both blocks are gated by Settings.System codex_widgets_enabled == 1.
Java.perform(function () {
  const runtime = globalThis.__watchWorkshop;
  if (!runtime || !runtime.enabled('widgets')) return;
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
    runtime.report('widgets', error);
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
  const runtime = globalThis.__watchWorkshop;
  if (!runtime || !runtime.enabled('widgets')) return;
  const Log = Java.use('android.util.Log');
  function qlog(m) { try { Log.i('QuickCenterPlus', String(m)); } catch (_) {} }
  function qerr(e) {
    qlog('hide-breeno error: ' + e + ' @ ' + (e && e.stack ? String(e.stack).split('\n').slice(0, 3).join(' | ') : ''));
    try { runtime.report('widgets', e); } catch (_) {}
  }
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
  } catch (e) {
    qlog('hide-breeno unavailable: ' + e);
    runtime.report('widgets', e);
  }
});
