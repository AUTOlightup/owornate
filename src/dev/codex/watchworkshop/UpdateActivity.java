package dev.codex.watchworkshop;

import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/** Explicit check -> download -> review -> install, with no background update scheduling. */
public final class UpdateActivity extends WatchActivity {
    private static final int ALLOW_INSTALL = 91, INSTALL = 92;
    private String pluginId = "", title = "管理器更新", source = "", currentName = "";
    private String status = "", error = "";
    private int currentVersion;
    private boolean busy, readyApk;
    private String apkVersionName = "";
    private UpdateClient.Release release;
    private final UpdateClient client = new UpdateClient();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        String id = getIntent().getStringExtra("pluginId");
        if (id != null) pluginId = id;
        try {
            loadCurrent();
            if (pluginId.isEmpty() && UpdateApkProvider.apk(this).isFile()) {
                apkVersionName = UpdateApkProvider.validate(this, UpdateApkProvider.apk(this)).versionName;
                readyApk = true;
            }
        } catch (Exception problem) { error = message(problem); }
        render();
    }

    private void loadCurrent() throws Exception {
        if (pluginId.isEmpty()) {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            currentName = info.versionName;
            currentVersion = info.versionCode;
            source = UpdateClient.DEFAULT_SOURCE;
        } else {
            PluginManifest plugin = ExternalPlugins.repository(this).load(pluginId);
            if (plugin == null) throw new IOException("插件已移除");
            title = plugin.name + "更新";
            currentName = plugin.versionName;
            currentVersion = plugin.versionCode;
            source = plugin.updateUrl.isEmpty() ? UpdateClient.DEFAULT_SOURCE : plugin.updateUrl;
        }
    }

    @Override public void onBackPressed() { if (!busy) super.onBackPressed(); }

    private void check() {
        if (busy) return;
        try { loadCurrent(); } catch (Exception problem) { error = message(problem); render(); return; }
        busy = true; error = ""; release = null; status = "正在检查更新…";
        render();
        String url = source, id = pluginId.isEmpty() ? UpdateClient.MANAGER_ID : pluginId;
        int version = currentVersion;
        inBackground(DeviceQueues.GENERAL, () -> client.check(url, id, version), (result, problem) -> {
            busy = false;
            if (problem != null) { status = ""; error = message(problem); }
            else { release = result; status = result == null ? "更新源没有更高版本。" : "发现可用更新。"; }
            render();
        });
    }

    private void download() {
        if (busy || release == null) return;
        UpdateClient.Release chosen = release;
        busy = true; error = ""; status = "正在下载并校验…"; readyApk = false;
        render();
        android.content.Context app = getApplicationContext();
        String id = pluginId;
        inBackground(DeviceQueues.GENERAL, () -> {
            File dir = new File(app.getFilesDir(), "updates");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法创建更新下载目录");
            File file = id.isEmpty() ? UpdateApkProvider.apk(app) : new File(dir, "plugin.owo");
            client.download(chosen, file, id.isEmpty() ? 32 * 1024 * 1024 : 2 * 1024 * 1024);
            if (id.isEmpty()) {
                try {
                    PackageInfo next = UpdateApkProvider.validate(app, file);
                    if (next.versionCode != chosen.versionCode || !chosen.versionName.equals(next.versionName)) {
                        throw new IOException("APK 版本与更新清单不一致");
                    }
                } catch (Exception problem) { file.delete(); throw problem; }
                return "";
            }
            PluginRepository repository = ExternalPlugins.repository(app);
            PluginRepository.Pending pending = null;
            try {
                pending = repository.stage(new FileInputStream(file));
                if (!id.equals(pending.manifest.id) || pending.manifest.versionCode != chosen.versionCode
                        || !chosen.versionName.equals(pending.manifest.versionName)) {
                    throw new IOException("插件标识或版本与更新清单不一致");
                }
                repository.upgradeFrom(pending);
                return pending.token;
            } catch (Exception problem) {
                if (pending != null) repository.cancel(pending);
                throw problem;
            } finally { file.delete(); }
        }, (token, problem) -> {
            busy = false;
            if (problem != null) { status = ""; error = message(problem); render(); return; }
            if (!id.isEmpty()) {
                startActivity(new Intent(this, PluginActivity.class).putExtra("pendingUpdate", token)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
                finish();
            } else {
                readyApk = true;
                apkVersionName = chosen.versionName;
                status = "下载完成，包名、版本和原签名已校验。确认后由系统安装器覆盖升级，保留应用数据。";
                render();
            }
        });
    }

    private void install() {
        if (busy || !readyApk) return;
        try {
            UpdateApkProvider.validate(this, UpdateApkProvider.apk(this));
            if (!getPackageManager().canRequestPackageInstalls()) {
                status = "请允许 OWOrnate 安装更新，返回后再次点击确认安装。";
                Uri packageUri = Uri.parse("package:" + getPackageName());
                try {
                    startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            packageUri), ALLOW_INSTALL);
                } catch (ActivityNotFoundException | SecurityException unavailable) {
                    status = "请在应用信息中开启“允许来自此来源的应用”，返回后再次确认安装。";
                    startActivityForResult(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            packageUri), ALLOW_INSTALL);
                }
                render(); return;
            }
            Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE)
                    .setDataAndType(UpdateApkProvider.uri(this), "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .putExtra(Intent.EXTRA_RETURN_RESULT, true);
            startActivityForResult(intent, INSTALL);
        } catch (Exception problem) { error = "无法打开安装流程：" + message(problem); render(); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == INSTALL) {
            status = result == RESULT_OK ? "安装流程已完成。" : "安装已取消或未完成，当前版本保持不变。";
            try { loadCurrent(); } catch (Exception problem) { error = message(problem); }
        }
        render();
    }

    private void render() {
        rebuild("updates", () -> {
            ui.header(title, false, this::onBackPressed, null);
            ui.value("当前版本", currentName + " (" + currentVersion + ")");
            if (!status.isEmpty()) ui.note(status);
            if (!error.isEmpty()) ui.note(error, "warning");
            if (release != null) {
                ui.value("新版本", release.versionName + " (" + release.versionCode + ")");
                if (!release.notes.isEmpty()) ui.note(release.notes);
                ui.button("下载更新", true, !busy, this::download);
            }
            if (readyApk) {
                ui.value("已下载版本", apkVersionName);
                ui.button("确认安装管理器更新", true, !busy, this::install);
            }
            ui.button("检查更新", false, !busy, this::check);
            ui.value("更新源", source);
            ui.note(pluginId.isEmpty() ? "检查不会自动安装。更新沿用当前签名并保留数据。"
                    : "下载后还需确认升级。脚本插件请先停用；Launcher 包升级后按需另行更新组件。");
        });
    }
}
