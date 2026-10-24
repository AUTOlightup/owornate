package dev.codex.watchworkshop;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONObject;
import dev.codex.watchworkshop.ModuleManagement.Action;

/** launcher-v1 adapts imported payloads to the existing device lifecycle and native settings. */
final class LauncherPlugins {
    static final String[] FEATURES = {"monet", "widgets", "capture"};
    static String id(String feature) {
        return java.util.Arrays.asList(FEATURES).contains(feature) ? "dev.owornate." + feature : feature;
    }
    private static final String CONTROL = "/data/user/0/dev.codex.watchworkshop/files/monet/runtime-manage.sh";

    /** Called under RuntimeSupport.LOCK; never supplies a bundled fallback for a missing package. */
    static void prepare(Context context) throws IOException {
        PluginRepository repository = ExternalPlugins.repository(context);
        File source = new File(context.getFilesDir(), "monet/plugins");
        for (String feature : FEATURES) {
            PluginManifest manifest = repository.load(id(feature));
            File directory = new File(source, feature);
            if (Files.isSymbolicLink(directory.toPath())) throw new IOException("插件载荷目录不能是符号链接");
            if (manifest == null) {
                Files.deleteIfExists(new File(directory, "hook.js").toPath());
                Files.deleteIfExists(new File(directory, "theme.jar").toPath());
                continue;
            }
            if (!"launcher-v1".equals(manifest.runtime)) throw new IOException("内置功能标识被其他协议占用");
            byte[] hook = repository.payload(manifest.id, "hook.js");
            byte[] jar = feature.equals("monet") ? repository.payload(manifest.id, "theme.jar") : null;
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法准备插件载荷");
            writeIfChanged(new File(directory, "hook.js"), hook);
            if (jar != null) writeIfChanged(new File(directory, "theme.jar"), jar);
        }
        Set<String> present = new HashSet<>();
        for (PluginManifest manifest : repository.list()) {
            if (!manifest.launcherExtension()) continue;
            present.add(manifest.id);
            File directory = new File(source, manifest.id);
            if (Files.isSymbolicLink(directory.toPath())) throw new IOException("插件载荷目录不能是符号链接");
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法准备插件载荷");
            writeIfChanged(new File(directory, "hook.js"), repository.payload(manifest.id, "hook.js"));
        }
        File[] directories = source.listFiles();
        if (directories != null) for (File directory : directories) {
            if (!PluginManifest.isSafeId(directory.getName()) || present.contains(directory.getName())) continue;
            if (Files.isSymbolicLink(directory.toPath())) throw new IOException("插件载荷目录不能是符号链接");
            Files.deleteIfExists(new File(directory, "hook.js").toPath());
            // Deployment payloads are separate; only remove the obsolete import staging directory.
            if (directory.isDirectory() && directory.list() != null && directory.list().length == 0) {
                Files.deleteIfExists(directory.toPath());
            }
        }
    }

    private static void writeIfChanged(File file, byte[] bytes) throws IOException {
        if (Files.isSymbolicLink(file.toPath())) throw new IOException("插件载荷不能是符号链接");
        if (file.isFile() && file.length() == bytes.length
                && java.util.Arrays.equals(Files.readAllBytes(file.toPath()), bytes)) return;
        Payloads.writeAtomically(file, bytes);
    }

    static final class Snapshot {
        final String kind, owner, text;
        final boolean installed;
        final Boolean enabled;
        Snapshot(String kind, String owner, String text, boolean installed, Boolean enabled) {
            this.kind = kind; this.owner = owner; this.text = text;
            this.installed = installed; this.enabled = enabled;
        }
        EnumSet<Action> actions() {
            EnumSet<Action> actions = EnumSet.of(Action.REFRESH);
            if (kind.equals("conflict") || owner.isEmpty()) return actions;
            actions.add(Action.UNINSTALL); // remove the imported package; deployed own code is cleaned first
            if (!owner.equals("own")) return actions;
            if (kind.equals("available")) actions.add(Action.INSTALL);
            if (installed) {
                actions.add(Action.UPDATE);
                if (enabled != null) actions.addAll(EnumSet.of(Action.ENABLE, Action.DISABLE));
            }
            return actions;
        }
        String limitation() {
            if (owner.equals("shared")) return "当前使用共享组件；此插件包尚未部署，不会替换共享 Hook 或创建第二套运行时。";
            if (kind.equals("setup")) return "请先完成管理器基础部署，再安装此插件。";
            return "";
        }
    }

    static Snapshot read(Context context, String feature) throws Exception {
        switch (feature) {
            case "monet": {
                ModuleState s = ModuleManager.read(context);
                return new Snapshot(s.kind, s.owner, s.message, s.installed(),
                        s.activeKnown && s.installed() ? s.enabled() : null);
            }
            case "widgets": {
                WidgetHostManager.Status s = WidgetHostManager.check(context);
                Boolean enabled = s.host.equals("ready") || s.host.equals("installed") ? Boolean.TRUE
                        : s.host.equals("disabled") ? Boolean.FALSE : null;
                return new Snapshot(s.host, s.owner, new CaptureModuleState(s.host, s.owner).message(),
                        s.installed(), enabled);
            }
            case "capture": {
                CaptureModuleState s = WidgetHostManager.captureState(context);
                return new Snapshot(s.kind, s.owner, s.message(), s.installed(),
                        s.enabledKnown() ? s.enabled() : null);
            }
            default:
                requireExtension(feature);
                ModuleManager.prepare(context);
                return RootShell.session(context, 1800, 35000, adb -> readExtension(adb, feature));
        }
    }

    private static void requireExtension(String id) {
        if (!PluginManifest.isLauncherId(id) || PluginManifest.isOfficialLauncherId(id)) {
            throw new IllegalArgumentException("无效的 Launcher 插件 ID");
        }
    }

    private static String control(AdbClient adb, String action, String id) throws Exception {
        requireExtension(id);
        if (!java.util.Arrays.asList("kind", "status", "install", "enable", "disable", "update", "uninstall").contains(action)) {
            throw new IllegalArgumentException("Launcher 插件操作");
        }
        String output = adb.shell("sh " + CONTROL + " " + action + " '" + id + "'", 45000).trim();
        if (adb.exitCode() != 0) throw new IOException(output.isEmpty() ? "插件操作未完成" : output);
        return output;
    }

    private static Snapshot readExtension(AdbClient adb, String id) throws Exception {
        String owner = control(adb, "kind", id);
        if (!java.util.Arrays.asList("own", "shared", "setup", "conflict").contains(owner)) {
            throw new IOException("未收到有效的插件运行环境状态");
        }
        if (!owner.equals("own")) return new Snapshot(owner, owner,
                owner.equals("shared") ? "共享环境 · 第三方载荷未部署" : owner.equals("setup")
                        ? "请先完成管理器基础部署" : "运行环境来源不明，请先检查", false, null);
        String status = control(adb, "status", id);
        switch (status) {
            case "available": return new Snapshot(status, owner, "未安装 · 可在手表安装", false, null);
            case "disabled": return new Snapshot(status, owner, "已安装 · 已停用", true, false);
            case "ready": return new Snapshot(status, owner, "已安装 · 已启用", true, true);
            case "installed": {
                String detail = "";
                String raw = adb.shell("cat /data/user_de/0/com.heytap.wearable.launcher/files/watchworkshop-runtime.json 2>/dev/null || true", 10000).trim();
                if (!raw.isEmpty()) {
                    try { detail = new JSONObject(raw).optString("error", ""); }
                    catch (org.json.JSONException invalid) { detail = "运行记录无法解析"; }
                }
                return new Snapshot(status, owner, "组件已保存 · 启动未确认"
                        + (detail.isEmpty() ? "" : "；运行记录：" + detail), true, true);
            }
            case "update": return new Snapshot(status, owner, "组件需要更新", true, null);
            default: throw new IOException("未收到有效的插件运行状态：" + status);
        }
    }

    private static void mutateExtension(Context context, String id, Action action) throws Exception {
        requireExtension(id);
        ModuleManager.prepare(context);
        RootShell.session(context, 1800, 35000, adb -> {
            Snapshot before = readExtension(adb, id);
            String command;
            switch (action) {
                case INSTALL: command = "install"; break;
                case ENABLE: command = "enable"; break;
                case DISABLE: command = "disable"; break;
                case UPDATE: command = "update"; break;
                case UNINSTALL: command = "uninstall"; break;
                default: throw new IOException("当前状态不支持此插件操作");
            }
            control(adb, command, id);
            if (action == Action.INSTALL) control(adb, "enable", id);
            Snapshot after = readExtension(adb, id);
            if (action == Action.UNINSTALL && after.installed) throw new IOException("卸载未确认，保留插件包");
            if (action == Action.DISABLE && !"disabled".equals(after.kind)) throw new IOException("插件停用未确认");
            if ((action == Action.ENABLE || action == Action.INSTALL
                    || action == Action.UPDATE && Boolean.TRUE.equals(before.enabled)) && !"ready".equals(after.kind)) {
                throw new IOException(after.text);
            }
            if (action == Action.UPDATE && Boolean.FALSE.equals(before.enabled) && !Boolean.FALSE.equals(after.enabled)) {
                throw new IOException("更新未保留原停用状态");
            }
            return null;
        });
    }

    static Snapshot execute(Context context, String feature, Action action) throws Exception {
        // Existing capture code acquires CaptureManager.class before RuntimeSupport.LOCK.
        // Keep that order for calls made through this new entry, too.
        synchronized (CaptureManager.class) { synchronized (RuntimeSupport.LOCK) {
            Snapshot before = read(context, feature);
            if (action == Action.REFRESH) return before;
            if (!before.actions().contains(action)) throw new IOException("当前状态不支持此操作");
            if (action == Action.UNINSTALL && !before.owner.equals("own")) {
                CaptureSettings.preserve(context, ExternalPlugins.repository(context).load(id(feature)));
                ExternalPlugins.repository(context).remove(id(feature));
                prepare(context);
                return before;
            }
            if (!(action == Action.UNINSTALL && !before.installed)) {
                switch (feature) {
                    case "monet":
                        if (action == Action.INSTALL) ModuleManager.install(context);
                        else if (action == Action.UPDATE) ModuleManager.update(context);
                        else if (action == Action.UNINSTALL) ModuleManager.uninstall(context);
                        else ModuleManager.setEnabled(context, action == Action.ENABLE);
                        break;
                    case "widgets":
                        if (action == Action.INSTALL) WidgetHostManager.install(context);
                        else if (action == Action.UPDATE) WidgetHostManager.update(context);
                        else if (action == Action.UNINSTALL) WidgetHostManager.uninstall(context);
                        else WidgetHostManager.setEnabled(context, action == Action.ENABLE);
                        break;
                    case "capture":
                        if (action == Action.INSTALL) WidgetHostManager.enableCapture(context);
                        else if (action == Action.UPDATE) WidgetHostManager.updateCapture(context);
                        else if (action == Action.UNINSTALL) WidgetHostManager.uninstallCapture(context);
                        else WidgetHostManager.setCaptureEnabled(context, action == Action.ENABLE);
                        break;
                    default: mutateExtension(context, feature, action); break;
                }
            }
            Snapshot after = read(context, feature);
            if (action == Action.UNINSTALL) {
                if (after.installed) throw new IOException("卸载未确认，保留插件包");
                CaptureSettings.preserve(context, ExternalPlugins.repository(context).load(id(feature)));
                ExternalPlugins.repository(context).remove(id(feature));
                prepare(context);
            }
            return after;
        } }
    }

    private LauncherPlugins() {}
}
