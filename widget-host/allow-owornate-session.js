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
