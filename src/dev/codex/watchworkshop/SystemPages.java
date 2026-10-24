package dev.codex.watchworkshop;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.StatFs;
import android.util.DisplayMetrics;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import org.json.JSONObject;

/**
 * 欢迎页、设置（原“设置”和“关于”合成一页）、运行环境、首次设置、技术详情和开源许可。
 */
final class SystemPages {
    private final MainActivity a;
    private JSONObject snapshot;
    private boolean probing;
    private boolean repairing;
    private String probeError = "";
    boolean busy() { return repairing; }

    SystemPages(MainActivity activity) {
        a = activity;
        try {
            snapshot = new JSONObject(activity.prefs.getString("probe", "{}"));
            if (snapshot.length() == 0) snapshot = null;
        } catch (Exception ignored) {
            snapshot = null;
        }
    }

    static String appVersion(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "未知版本";
        }
    }

    private static String time(long t) {
        return t <= 0 ? "尚无记录" : new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(t));
    }

    /** 后台准备本机密钥（第一次要生成，约几秒），排在莫奈的第一次读取前面。 */
    void prepareKeys() {
        a.inBackground(DeviceQueues.GENERAL, () -> {
            RootShell.prime(a);
            return null;
        }, (unused, problem) -> {
            if (problem == null) return;
            probeError = "本机密钥准备失败";
            a.render();
        });
    }

    void registerPages(PageRegistry pages) {
        pages.add(PageIds.WELCOME, "OWOrnate", this::welcome);
        pages.add(PageIds.SETTINGS, "设置", this::settings);
        pages.add(PageIds.CHECK, "运行环境", this::check);
        pages.add(PageIds.GUIDE, "首次设置", this::guide);
        pages.add(PageIds.TECHNICAL, "技术详情", this::technical);
        pages.add(PageIds.LICENSES, "开源许可", this::licenses);
    }

    private void welcome() {
        a.ui.hero("spark", "让优化，留在腕上", "分类安装 · 随时管理", "");
        a.ui.button("开始检查", true, () -> {
            a.prefs.edit().putBoolean("welcomed", true).apply();
            a.go(PageIds.CHECK);
            probe();
        });
        a.ui.button("先逛逛", false, () -> {
            a.prefs.edit().putBoolean("welcomed", true).apply();
            a.returnHome();
        });
    }

    /** 设置：运行环境和说明类入口在上，版本信息在下。 */
    private void settings() {
        WorkshopUi ui = a.ui;
        ui.row("运行环境", "授权、组件和模块接入状态", "shield", () -> a.go(PageIds.CHECK));
        ui.row("首次设置", "安装与恢复说明", "book", () -> a.go(PageIds.GUIDE));
        ui.row("技术详情", "授权与组件信息", "info", () -> a.go(PageIds.TECHNICAL));
        ui.row("开源许可", "Material Color Utilities 与 Frida", "file", () -> a.go(PageIds.LICENSES));
        ui.row("管理器更新", "检查 GitHub 发布版本", "info", () ->
                a.startActivity(new android.content.Intent(a, UpdateActivity.class)));
        ui.title("关于 OWOrnate");
        ui.value("版本", appVersion(a));
        ui.value("包名", a.getPackageName());
        ui.note("管理控制中心配色、连续圆角、截屏与录屏，以及负一屏信息、倒计时和课表小组件。内置功能可离线配置；首次部署需电脑和现有 root adbd。");
    }

    private void probe() {
        if (probing) return;
        probing = true;
        probeError = "";
        a.render();
        a.inBackground(DeviceQueues.GENERAL, () -> {
            JSONObject result = RuntimeProbe.read(a);
            if (!a.prefs.edit().putString("probe", result.toString()).commit()) throw new IOException("检测结果保存失败");
            return result;
        }, (result, problem) -> {
            probing = false;
            if (problem == null) snapshot = result;
            else probeError = WatchActivity.message(problem);
            a.render();
        });
    }

    private void recheck() {
        probe();
        a.refresh(true);
    }

    /** Explicit user action; opening the manager or checking status never calls ensure. */
    private void repairRuntime() {
        if (repairing || a.working()) return;
        repairing = true;
        a.render();
        a.inBackground(DeviceQueues.GENERAL, () -> {
            RuntimeSupport.ensure(a.getApplicationContext(), false);
            return null;
        }, (unused, problem) -> {
            repairing = false;
            recheck();
        });
    }

    private void check() {
        WorkshopUi ui = a.ui;
        MonetPages monet = a.monet;
        boolean reading = probing || a.anyRefreshing();
        boolean probeFailed = !probeError.isEmpty();
        boolean moduleFailed = a.anyReadFailed();
        if (reading) {
            ui.hero("scan", "正在检查", "读取设备、授权和模块状态。", "");
        } else if (probeFailed || moduleFailed) {
            ui.hero("key", "状态读取未完成", "设备检查与功能状态分别显示在下方。", "warning");
            if (probeFailed) {
                ui.note("设备检查或授权失败：" + probeError, "warning");
                ui.row("首次设置", "查看部署步骤", "book", () -> a.go(PageIds.GUIDE));
            }
            if (moduleFailed) {
                ui.note("功能状态读取失败。已确认的旧值仍保留，不影响其他功能。");
                a.readErrorNotes("warning");
            }
        } else if (needsSetup()) {
            ui.hero("key", "需要首次部署", "完成管理器首次部署后，可在手表分别安装功能。", "warning");
            ui.row("首次设置", "查看部署步骤", "book", () -> a.go(PageIds.GUIDE));
        } else if (managerReady()) {
            int count = installedCount();
            String detail = count == 0
                    ? "运行环境已就绪 · 三项功能可按任意顺序安装"
                    : count + " 项功能已纳入管理";
            ui.hero("shield", "管理器已就绪", detail, "success");
            ui.value("莫奈确认时间", time(monet.confirmedAt()));
            ui.button("发现优化", true, () -> a.go(PageIds.DISCOVER));
            ui.button("返回首页", false, a::returnHome);
        } else if (incompatible()) {
            ui.hero("warning", "运行组件需要检查", "现有文件不兼容或来源无法确认。", "warning");
            ui.note("可重新读取状态；若仍失败，请用电脑检查首次部署与运行组件版本。");
        } else if (monet.known()) {
            ui.hero("key", "状态待确认", monet.statusText(), "warning");
            ui.value("莫奈确认时间", time(monet.confirmedAt()));
        } else {
            ui.note("尚未执行检查。");
        }
        if (!reading) {
            device();
            ui.button("重新检查", false, !repairing && !a.working(), this::recheck);
            if (monet.ownRuntime()) {
                ui.button(repairing ? "正在恢复启动…" : "恢复独立组件启动", false,
                        !repairing && !a.working(), this::repairRuntime);
                ui.note(RuntimeSupport.lastResult(a));
            }
        }
    }

    private int installedCount() {
        int count = 0;
        if (a.monet.installed()) count++;
        if (a.widgets.installed()) count++;
        if (a.capture.installed()) count++;
        return count;
    }

    private boolean managerReady() {
        return a.monet.confirmedAt() > 0 && a.monet.known() && a.widgets.confirmed()
                && a.capture.confirmed() && !needsSetup() && !incompatible();
    }

    private boolean needsSetup() {
        return a.monet.needsSetup() || a.widgets.needsSetup() || a.capture.needsSetup();
    }

    private boolean incompatible() {
        return a.monet.incompatible() || a.widgets.incompatible() || a.capture.incompatible();
    }

    private void device() {
        WorkshopUi ui = a.ui;
        ui.value("型号", Build.MODEL);
        ui.value("系统", Build.DISPLAY);
        ui.value("Android", Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT);
        ui.value("ABI", android.text.TextUtils.join(", ", Build.SUPPORTED_ABIS));
        DisplayMetrics m = a.getResources().getDisplayMetrics();
        ui.value("当前画布", m.widthPixels + " × " + m.heightPixels + "px / density " + m.density);
        StatFs stat = new StatFs(a.getFilesDir().getAbsolutePath());
        ui.value("可用空间", (stat.getAvailableBytes() / 1024 / 1024) + " MB");
        ui.value("适配范围", "OPPO Watch 2 46mm / A.92");
        ui.value("负一屏小组件", a.widgets.statusText());
        ui.value("截屏与录屏", a.capture.statusText());
    }

    private void guide() {
        WorkshopUi ui = a.ui;
        ui.hero("book", "只需部署一次", "之后在手表上安装和管理优化。", "");
        ui.text("1. 在已 root 的手表上，使用电脑完成管理器首次部署。\n2. 回到 OWOrnate，打开“发现优化”。\n3. 莫奈取色、负一屏小组件、截屏与录屏可按任意顺序安装。");
        ui.note("首次部署入口：Windows 包内的 00-Setup.cmd。完成后，安装功能无需电脑或联网。");
        ui.text("每个功能独立显示安装、启用、停用和更新状态；共享运行组件不能单独卸载，可停用其中的功能。");
        ui.button("重新检查", true, () -> {
            a.go(PageIds.CHECK);
            recheck();
        });
    }

    private void technical() {
        WorkshopUi ui = a.ui;
        ui.value("App", a.getPackageName() + " / " + appVersion(a));
        ui.value("本机通道", AdbClient.HOST + ":" + AdbClient.PORT + " · root adbd");
        ui.value("截屏与录屏", a.capture.statusText());
        if (a.capture.failed()) ui.note("截屏与录屏：" + a.capture.error(), "warning");
        if (snapshot != null) {
            ui.value("组件指纹", snapshot.optString("themeSha", "未读取"));
            ui.value("设备检查时间", time(snapshot.optLong("checkedAt")));
        }
        ui.button("导出当前配置", false, a.monet.exportable(), a.monet::exportDraft);
        ui.value("启动记录", RuntimeSupport.lastResult(a));
        SharedPreferences r = a.getSharedPreferences("runtime", 0);
        ui.value("系统启动记录", r.getString("bootId", "").isEmpty() ? "尚无本包独立启动记录"
                : r.getBoolean("bootReady", false) ? "上次启动功能已就绪 · 当前启动请用电脑验证" : "上次启动的功能就绪未确认");
    }

    private void licenses() {
        WorkshopUi ui = a.ui;
        String[] licenseFiles = {
            "material-color-utilities.txt", "frida-core.txt", "frida-core-library.txt", "frida-gum.txt"
        };
        for (String file : licenseFiles) {
            ui.title(file);
            try (InputStream in = a.getAssets().open("licenses/" + file)) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] b = new byte[4096];
                int n;
                while ((n = in.read(b)) != -1) out.write(b, 0, n);
                ui.text(out.toString("UTF-8"));
            } catch (IOException e) {
                ui.note("完整许可随源码与安装包提供。");
            }
        }
    }
}
