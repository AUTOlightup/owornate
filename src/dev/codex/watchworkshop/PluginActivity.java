package dev.codex.watchworkshop;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import dev.codex.watchworkshop.ModuleManagement.Action;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 插件共用管理与声明式设置页，不加载插件提供的 Activity 或 Java 类。 */
public final class PluginActivity extends WatchActivity implements ModuleManagement {
    private static final int PICK = 81, STORAGE = 82;
    private ModuleManagementUi managementUi = new ModuleManagementUi();
    private PluginRepository repository;
    private List<PluginManifest> plugins = new ArrayList<>();
    private List<File> downloads = new ArrayList<>();
    private PluginManifest manifest;
    private PluginRepository.Pending pending;
    private Map<String, String> config = new LinkedHashMap<>();
    private String selected = "", pendingToken = "", setting = "";
    private String error = "", operationError = "", notice = "";
    private boolean busy, confirmed, showDownloads;
    private boolean configurationLoaded;
    private String configurationError = "";
    private Boolean enabled;
    private LauncherPlugins.Snapshot launcherState;

    private boolean launcher() { return manifest != null && manifest.runtime.equals("launcher-v1"); }
    private boolean monetSettings() { return manifest != null && MonetSettings.PLUGIN_ID.equals(manifest.id)
            && !manifest.nativeSettings.isEmpty(); }
    private boolean canConfigure() { return !busy && (launcher() ? configurationLoaded : confirmed); }

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        repository = ExternalPlugins.repository(this);
        selected = saved == null ? getIntent().getStringExtra("pluginId") : saved.getString("pluginId", "");
        if (selected == null) selected = "";
        if (saved != null) {
            pendingToken = saved.getString("pending", "");
            setting = saved.getString("setting", "");
        } else {
            String incoming = getIntent().getStringExtra("pendingUpdate");
            if (incoming != null) pendingToken = incoming;
        }
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        if (!busy) load();
    }

    @Override protected void onSaveInstanceState(Bundle saved) {
        super.onSaveInstanceState(saved);
        saved.putString("pluginId", selected);
        saved.putString("pending", pendingToken);
        saved.putString("setting", setting);
    }

    @Override public void onBackPressed() {
        if (busy) return;
        if (pending != null) { cancelImport(); return; }
        if (!setting.isEmpty()) { setting = ""; render(); return; }
        if (showDownloads) { showDownloads = false; render(); return; }
        if (!selected.isEmpty()) {
            managementUi = new ModuleManagementUi();
            selected = "";
            manifest = null;
            error = "";
            operationError = "";
            load();
        } else super.onBackPressed();
    }

    private void load() {
        busy = true;
        configurationLoaded = false;
        configurationError = "";
        render();
        inBackground(DeviceQueues.GENERAL, () -> {
            Loaded result = new Loaded();
            result.plugins = repository.list();
            if (!pendingToken.isEmpty()) {
                try { result.pending = repository.pending(pendingToken); }
                catch (RuntimeException missing) { result.pendingError = message(missing); }
            }
            if (!selected.isEmpty()) {
                result.manifest = repository.load(selected);
                if (result.manifest == null) throw new java.io.IOException("插件已移除，请返回列表");
                result.config = result.manifest.runtime.equals(PluginManifest.LAUNCHER_RUNTIME)
                        ? (CaptureSettings.PLUGIN_ID.equals(result.manifest.id)
                                ? CaptureSettings.config(this, result.manifest) : new LinkedHashMap<>())
                        : repository.config(selected);
            }
            return result;
        }, (result, problem) -> {
            busy = false;
            if (problem != null) {
                error = message(problem);
                confirmed = false;
                render();
                return;
            }
            plugins = result.plugins;
            pending = result.pending;
            if (pending == null) pendingToken = "";
            manifest = result.manifest;
            if (manifest != null) { config = result.config; configurationLoaded = !monetSettings(); }
            error = result.pendingError;
            render();
            if (manifest != null && pending == null) {
                if (launcher()) runLauncher(Action.REFRESH);
                else run("status", null);
            }
        });
    }

    private static final class Loaded {
        List<PluginManifest> plugins;
        PluginRepository.Pending pending;
        PluginManifest manifest;
        Map<String, String> config;
        String pendingError = "";
    }

    private void pick() {
        if (busy) return;
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK);
        } catch (ActivityNotFoundException missing) {
            notice = "没有系统文件选择器，可从下载目录导入。";
            openDownloads();
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK && result == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            stage(() -> getContentResolver().openInputStream(uri));
        }
    }

    private interface Source { InputStream open() throws Exception; }

    private void stage(Source source) {
        if (busy) return;
        busy = true;
        error = "";
        notice = "";
        render();
        inBackground(DeviceQueues.GENERAL, () -> {
            if (pending != null) repository.cancel(pending);
            return repository.stage(source.open());
        }, (result, problem) -> {
            busy = false;
            pending = result;
            pendingToken = result == null ? "" : result.token;
            if (problem != null) error = message(problem);
            showDownloads = false;
            render();
        });
    }

    private void acceptImport() {
        if (busy || pending == null) return;
        PluginRepository.Pending accepted = pending;
        busy = true;
        error = "";
        render();
        android.content.Context app = getApplicationContext();
        inBackground(DeviceQueues.GENERAL, () -> ExternalPlugins.importPackage(app, accepted), (installed, problem) -> {
            busy = false;
            if (problem != null) { error = message(problem); render(); return; }
            pending = null;
            pendingToken = "";
            selected = installed.id;
            manifest = installed;
            notice = installed.runtime.equals("launcher-v1")
                    ? "导入包已保存；已部署组件保持原状，可按需更新组件。"
                    : "插件包已保存，配置与数据保留。启用前请检查设置。";
            confirmed = false;
            enabled = null;
            load();
        });
    }

    private void cancelImport() {
        if (busy || pending == null) return;
        PluginRepository.Pending cancelled = pending;
        busy = true;
        inBackground(DeviceQueues.GENERAL, () -> { repository.cancel(cancelled); return true; }, (ok, problem) -> {
            busy = false;
            if (problem != null) error = message(problem);
            else { pending = null; pendingToken = ""; error = ""; }
            render();
        });
    }

    private void openDownloads() {
        if (busy) return;
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, STORAGE);
            return;
        }
        busy = true;
        render();
        inBackground(DeviceQueues.GENERAL, () -> {
            File directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File[] files = directory.listFiles();
            if (files == null) throw new java.io.IOException("无法读取下载目录");
            List<File> result = new ArrayList<>();
            for (File file : files) {
                if (file.isFile() && file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".owo")) result.add(file);
            }
            java.util.Collections.sort(result, (a, b) -> a.getName().compareTo(b.getName()));
            return result;
        }, (result, problem) -> {
            busy = false;
            if (problem != null) error = message(problem);
            else { downloads = result; showDownloads = true; error = ""; }
            render();
        });
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == STORAGE) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) openDownloads();
            else { error = "未授予下载目录读取权限，可使用系统文件选择器。"; render(); }
        }
    }

    @Override public String moduleTitle() { return manifest == null ? "本地插件" : manifest.name; }

    @Override public State managementState() {
        if (launcher()) {
            EnumSet<Action> actions = launcherState == null ? EnumSet.of(Action.REFRESH) : launcherState.actions();
            return new State(true, launcherState == null ? null : launcherState.enabled, confirmed, busy,
                    launcherState == null ? "载荷已导入 · 部署状态待确认" : launcherState.text,
                    error, operationError, notice, launcherState == null ? "" : launcherState.limitation(), actions);
        }
        EnumSet<Action> actions = EnumSet.of(Action.REFRESH);
        if (manifest != null) actions.addAll(EnumSet.of(Action.ENABLE, Action.DISABLE, Action.UNINSTALL));
        String status = !confirmed ? "运行状态待确认" : Boolean.TRUE.equals(enabled) ? "已安装 · 已启用" : "已安装 · 已停用";
        return new State(manifest != null, enabled, confirmed, busy, status, error, operationError,
                notice, "", actions, true);
    }

    @Override public String uninstallMessage() {
        if (launcher()) return launcherState != null && launcherState.installed && launcherState.owner.equals("own")
                ? "先卸载此功能的自有运行载荷，确认后移除导入包。其他插件、主题、卡片和媒体保留。"
                : "移除此导入包，保留数据；当前共享运行组件不受影响。";
        return "先运行插件的停用与卸载清理，确认停用后移除安装包。插件配置和数据保留；清理失败则保留安装包。";
    }

    @Override public void executeManagement(Action action) {
        if (launcher()) { runLauncher(action); return; }
        switch (action) {
            case REFRESH: run("status", null); break;
            case ENABLE: run("enable", null); break;
            case DISABLE: run("disable", null); break;
            case UNINSTALL: run("uninstall", null); break;
            default: break;
        }
    }

    private void runLauncher(Action action) {
        if (busy || !launcher()) return;
        String feature = manifest.feature();
        busy = true; error = ""; operationError = ""; notice = "";
        boolean readTheme = monetSettings();
        if (readTheme) { configurationLoaded = false; config.clear(); configurationError = ""; }
        render();
        android.content.Context app = getApplicationContext();
        inBackground(DeviceQueues.GENERAL, () -> {
            LauncherResult result = new LauncherResult();
            result.state = LauncherPlugins.execute(app, feature, action);
            if (readTheme && action != Action.UNINSTALL) {
                if (Boolean.TRUE.equals(result.state.enabled)) {
                    try { result.config = MonetSettings.config(app); }
                    catch (Exception problem) { result.configError = message(problem); }
                } else result.configError = "请先启用莫奈并重新读取状态。";
            }
            return result;
        }, (result, problem) -> {
            busy = false; confirmed = problem == null;
            if (problem != null) {
                if (action == Action.REFRESH) error = message(problem);
                else operationError = message(problem);
            } else if (action == Action.UNINSTALL) {
                selected = ""; manifest = null; launcherState = null;
                notice = "本地插件包已移除，配置与数据保留。";
                load(); return;
            } else {
                launcherState = result.state;
                if (readTheme) {
                    configurationError = result.configError;
                    configurationLoaded = result.config != null;
                    if (configurationLoaded) config = result.config;
                }
                if (action != Action.REFRESH) notice = "操作已完成，状态已重新确认。";
            }
            render();
        });
    }

    private static final class LauncherResult {
        LauncherPlugins.Snapshot state;
        Map<String, String> config;
        String configError = "";
    }

    private void configureLauncher(String key, String value) {
        if (!canConfigure() || !launcher()) return;
        String id = manifest.id;
        busy = true; operationError = ""; notice = ""; configurationError = "";
        render();
        android.content.Context app = getApplicationContext();
        inBackground(DeviceQueues.GENERAL, () -> MonetSettings.PLUGIN_ID.equals(id)
                ? MonetSettings.configure(app, key, value)
                : CaptureSettings.configure(app, id, java.util.Collections.singletonMap(key, value)), (values, problem) -> {
            busy = false;
            if (problem != null) {
                operationError = message(problem);
                if (monetSettings()) {
                    configurationLoaded = false; confirmed = false; config.clear();
                    configurationError = "设置未确认，请刷新状态后重试。";
                }
            } else {
                config = values;
                notice = monetSettings() ? "主题设置已应用并确认。" : "设置已保存，下一段录屏使用此画质。";
            }
            render();
        });
    }

    private void run(String action, Map<String, String> next) {
        if (busy || manifest == null) return;
        String id = manifest.id;
        busy = true;
        error = "";
        operationError = "";
        render();
        android.content.Context app = getApplicationContext();
        inBackground(DeviceQueues.GENERAL, () -> ExternalPlugins.run(app, id, action, next), (state, problem) -> {
            busy = false;
            confirmed = problem == null;
            enabled = problem == null ? state : null;
            notice = "";
            if (problem != null) {
                if (action.equals("status")) error = message(problem);
                else operationError = message(problem);
            } else if (action.equals("uninstall")) {
                selected = "";
                manifest = null;
                notice = "插件已卸载，配置与数据保留。";
                load();
                return;
            } else if (action.equals("configure")) {
                config = new LinkedHashMap<>(next);
                notice = "设置已保存。";
            } else if (!action.equals("status")) notice = state ? "插件已启用。" : "插件已停用。";
            render();
        });
    }

    private void render() {
        String page = pending != null ? "plugin-preview" : showDownloads ? "plugin-downloads"
                : manifest == null ? "plugin-list" : "plugin:" + manifest.id + ":" + setting;
        rebuild(page, () -> {
            if (manifest != null && pending == null && !showDownloads && setting.isEmpty()) {
                ModuleManagementUi.header(ui, this::onBackPressed, this);
                ModuleManagementUi.status(ui, this);
                ui.note(manifest.description);
                ui.value("版本", manifest.versionName);
                ui.button("检查插件更新", false, !busy, () -> startActivity(
                        new Intent(this, UpdateActivity.class).putExtra("pluginId", manifest.id)));
                if (launcher() && !manifest.launcherExtension()) {
                    ui.note(manifest.nativeSettings.isEmpty() ? "载荷来自导入包；功能设置由管理器提供。"
                            : monetSettings() ? "选项来自插件包，选择后应用到当前主题。固定颜色与预览可在功能设置中调整。"
                            : "画质选项来自插件包，保存后用于下一段录屏。");
                    ui.button("打开功能设置", false, !busy, () -> {
                        if (manifest.feature().equals("capture")) startActivity(new Intent(this, CaptureSetupActivity.class));
                        else startActivity(new Intent(this, MainActivity.class).putExtra("pluginFeature", manifest.feature()));
                    });
                } else if (launcher()) {
                    ui.note("此插件在 Launcher 内运行；启停、更新和卸载共用管理器运行环境。");
                }
                if (!configurationError.isEmpty()) ui.note(configurationError, "warning");
                for (PluginManifest.Setting item : manifest.displaySettings()) {
                    ui.row(item.title, configurationLoaded ? item.options.get(config.get(item.key)) : "待读取", "sliders", () -> {
                        if (canConfigure()) { setting = item.key; render(); }
                    }).setEnabled(canConfigure());
                }
                managementUi.actions(ui, this, this::render);
                return;
            }
            ui.header(pending != null ? "确认导入" : showDownloads ? "下载目录" : "本地插件",
                    false, this::onBackPressed, null);
            if (!error.isEmpty()) ui.note(error, "warning");
            if (!notice.isEmpty()) ui.note(notice);
            if (busy) ui.note("正在处理，请稍候…");
            if (pending != null) {
                ui.value("插件", pending.manifest.name);
                ui.value("版本", pending.manifest.versionName);
                ui.value("标识", pending.manifest.id);
                PluginManifest previous = null;
                String upgradeProblem = "";
                try {
                    previous = repository.load(pending.manifest.id);
                    if (previous != null) repository.upgradeFrom(pending);
                } catch (Exception problem) { upgradeProblem = message(problem); }
                if (previous != null) {
                    ui.value("当前版本", previous.versionName);
                    ui.note(pending.manifest.runtime.equals("launcher-v1")
                            ? "升级导入包，保留配置和已部署组件；安装后可另行更新组件。"
                            : "先停用此插件再升级。保留配置和数据；包校验或新版状态检查失败时恢复原包。");
                }
                if (!upgradeProblem.isEmpty()) ui.note(upgradeProblem, "warning");
                ui.note(pending.manifest.description);
                if (!pending.manifest.updateUrl.isEmpty()) ui.value("更新源", pending.manifest.updateUrl);
                ui.note(pending.manifest.runtime.equals("launcher-v1")
                        ? "此插件会在 Launcher 中运行代码，并可使用 root 部署。只导入你信任的代码；导入后仍需安装到自有运行环境，不能直接替换共享组件。"
                        : "本地插件未经官方审核。导入后，它的状态读取和管理脚本均可使用 root，能够修改系统及应用数据。仅导入你信任的作者或自己编写的插件。", "warning");
                ui.button(previous == null ? "信任并导入" : "信任并升级", true,
                        !busy && upgradeProblem.isEmpty(), this::acceptImport);
                ui.button("取消", false, !busy, this::cancelImport);
            } else if (showDownloads) {
                ui.note("将 .owo 插件包放入 Download 目录后在此选择。");
                for (File file : downloads) ui.row(file.getName(), "", "layers", () -> stage(() -> new FileInputStream(file)));
                if (downloads.isEmpty()) ui.note("下载目录中没有 .owo 插件包。");
                ui.button("刷新目录", false, !busy, this::openDownloads);
            } else if (manifest != null && !setting.isEmpty()) {
                for (PluginManifest.Setting item : manifest.displaySettings()) {
                    if (!item.key.equals(setting)) continue;
                    ui.title(item.title);
                    for (Map.Entry<String, String> option : item.options.entrySet()) {
                        ui.choice(option.getValue(), "", option.getKey().equals(config.get(item.key)), () -> {
                            if (!canConfigure()) return;
                            Map<String, String> next = new LinkedHashMap<>(config);
                            next.put(item.key, option.getKey());
                            setting = "";
                            if (launcher()) configureLauncher(item.key, option.getKey());
                            else run("configure", next);
                        }).setEnabled(canConfigure());
                    }
                }
            } else {
                for (PluginManifest item : plugins) ui.row(item.name, item.versionName, "layers", () -> {
                    if (busy) return;
                    selected = item.id; confirmed = false; enabled = null; launcherState = null; notice = ""; load();
                });
                if (plugins.isEmpty()) ui.note("尚未导入本地插件。");
                ui.button("选择插件包", true, !busy, this::pick);
                ui.button("从下载目录导入", false, !busy, this::openDownloads);
                ui.button("刷新列表", false, !busy, this::load);
            }
        });
    }
}
