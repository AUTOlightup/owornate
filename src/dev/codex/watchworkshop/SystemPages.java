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
    private String probeError = "";

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
        MonetPages.IO.execute(() -> {
            try {
                RootShell.prime(a);
            } catch (Exception e) {
                a.runOnUiThread(() -> {
                    probeError = "本机密钥准备失败";
                    a.render();
                });
            }
        });
    }

    String title(String page) {
        switch (page) {
            case "settings": return "设置";
            case "check": return "运行环境";
            case "guide": return "首次设置";
            case "technical": return "技术详情";
            case "licenses": return "开源许可";
            default: return null;
        }
    }

    boolean render(String page) {
        switch (page) {
            case "welcome": welcome(); return true;
            case "settings": settings(); return true;
            case "check": check(); return true;
            case "guide": guide(); return true;
            case "technical": technical(); return true;
            case "licenses": licenses(); return true;
            default: return false;
        }
    }

    private void welcome() {
        a.ui.hero("spark", "让优化，留在腕上", "分类安装 · 随时管理", "");
        a.ui.button("开始检查", true, () -> {
            a.prefs.edit().putBoolean("welcomed", true).apply();
            a.go("check");
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
        ui.row("运行环境", "授权、组件和小组件接入状态", "shield", () -> a.go("check"));
        ui.row("首次设置", "安装与恢复说明", "book", () -> a.go("guide"));
        ui.row("技术详情", "授权与组件信息", "info", () -> a.go("technical"));
        ui.row("开源许可", "Material Color Utilities 与 Frida", "file", () -> a.go("licenses"));
        ui.title("关于 OWOrnate");
        ui.value("版本", appVersion(a));
        ui.value("包名", a.getPackageName());
        ui.note("管理控制中心配色、连续圆角，以及负一屏信息、倒计时和课表小组件。内置功能可离线配置；首次部署需电脑和现有 root adbd。");
    }

    private void probe() {
        if (probing) return;
        probing = true;
        probeError = "";
        a.render();
        MonetPages.IO.execute(() -> {
            try {
                JSONObject r = RuntimeProbe.read(a);
                if (!a.prefs.edit().putString("probe", r.toString()).commit()) throw new IOException("检测结果保存失败");
                a.runOnUiThread(() -> {
                    snapshot = r;
                    probing = false;
                    if (!a.destroyed()) a.render();
                });
            } catch (Exception e) {
                a.runOnUiThread(() -> {
                    probing = false;
                    probeError = WatchActivity.message(e);
                    if (!a.destroyed()) a.render();
                });
            }
        });
    }

    private void recheck() {
        probe();
        a.refresh(true);
    }

    private void check() {
        WorkshopUi ui = a.ui;
        MonetPages monet = a.monet;
        boolean reading = probing || monet.refreshing();
        if (reading) {
            ui.hero("scan", "正在检查", "读取设备、授权和模块状态。", "");
        } else if (!probeError.isEmpty() || monet.failed()) {
            ui.hero("key", "暂时无法管理", "本机连接或授权需要检查。", "warning");
            ui.note(probeError.isEmpty() ? monet.error() : probeError);
            ui.row("首次设置", "查看部署步骤", "book", () -> a.go("guide"));
        } else if (monet.known()) {
            String kind = monet.statusText();
            boolean ready = monet.installed() || monet.available();
            ui.hero(ready ? "shield" : "key", ready ? "管理器已就绪" : "需要检查环境", kind, ready ? "success" : "warning");
            if (snapshot != null) ui.value("检查时间", time(snapshot.optLong("checkedAt")));
            if (ready) ui.button("进入 OWOrnate", true, a::returnHome);
            else ui.row("首次设置", "查看部署步骤", "book", () -> a.go("guide"));
        } else {
            ui.note("尚未执行检查。");
        }
        if (!reading) {
            device();
            ui.button("重新检查", false, this::recheck);
        }
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
    }

    private void guide() {
        WorkshopUi ui = a.ui;
        ui.hero("book", "只需部署一次", "之后在手表上安装和管理优化。", "");
        ui.text("1. 在已 root 的手表上，使用电脑完成管理器首次部署。\n2. 回到 OWOrnate，在“发现优化”中打开莫奈取色。\n3. 查看安装方案，点“安装并启用”。");
        ui.note("首次部署入口：Windows 包内的 00-Setup.cmd。完成后，莫奈安装无需电脑或联网。");
        ui.text("已安装的功能直接列在首页，点进去即可配置；莫奈的启停在配置页右上开关，卸载在“更多选项”里。");
        ui.button("重新检查", true, () -> {
            a.go("check");
            recheck();
        });
    }

    private void technical() {
        WorkshopUi ui = a.ui;
        ui.value("App", a.getPackageName() + " / " + appVersion(a));
        ui.value("本机通道", AdbClient.HOST + ":" + AdbClient.PORT + " · root adbd");
        if (snapshot != null) {
            ui.value("组件指纹", snapshot.optString("themeSha", "未读取"));
            ui.value("检查时间", time(snapshot.optLong("checkedAt")));
        }
        ui.button("导出当前配置", false, a.monet.exportable(), a.monet::exportDraft);
        ui.value("启动记录", RuntimeSupport.lastResult(a));
        SharedPreferences r = a.getSharedPreferences("runtime", 0);
        ui.value("系统启动记录", r.getString("bootId", "").isEmpty() ? "尚无本包独立启动记录"
                : r.getBoolean("bootOk", false) ? "上次启动已确认 · 当前启动请用电脑验证" : "上次启动未确认");
    }

    private void licenses() {
        WorkshopUi ui = a.ui;
        for (String file : new String[]{"material-color-utilities.txt", "frida-core.txt", "frida-core-library.txt", "frida-gum.txt"}) {
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
