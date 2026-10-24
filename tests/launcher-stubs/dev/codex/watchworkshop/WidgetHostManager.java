package dev.codex.watchworkshop;

import android.content.Context;
import java.io.IOException;

/** Stateful widget/capture manager stub with the production call surface. */
final class WidgetHostManager {
    static final class Status {
        final String host, keep, owner;

        Status(String host, String keep, String owner) {
            this.host = host;
            this.keep = keep;
            this.owner = owner == null ? "" : owner;
        }

        boolean installed() {
            return "ready".equals(host) || "installed".equals(host) || "disabled".equals(host)
                    || "update".equals(host);
        }

        boolean enabled() { return "ready".equals(host) || "installed".equals(host); }

        boolean removable() { return installed() && "own".equals(owner); }
    }

    private static Status widget = widgetAvailable();
    private static CaptureModuleState capture = captureAvailable();
    static boolean failWidgetUninstall;
    static boolean failCaptureUninstall;

    private WidgetHostManager() {}

    static void reset() {
        widget = widgetAvailable();
        capture = captureAvailable();
        failWidgetUninstall = false;
        failCaptureUninstall = false;
    }

    static void setWidget(String host, String keep, String owner) {
        widget = new Status(host, keep, owner);
    }

    static void setCapture(String kind, String owner) {
        capture = new CaptureModuleState(kind, owner);
    }

    static Status check(Context context) { return widget; }

    static Status install(Context context) {
        ManagerCalls.add("widgets.install");
        widget = new Status("ready", "present", "own");
        return widget;
    }

    static Status update(Context context) {
        ManagerCalls.add("widgets.update");
        widget = new Status(widget.enabled() ? "ready" : "disabled", widget.keep, "own");
        return widget;
    }

    static Status setEnabled(Context context, boolean enabled) throws IOException {
        ManagerCalls.add(enabled ? "widgets.enable" : "widgets.disable");
        widget = new Status(enabled ? "ready" : "disabled", widget.keep, "own");
        return widget;
    }

    static Status uninstall(Context context) throws IOException {
        ManagerCalls.add("widgets.uninstall");
        if (failWidgetUninstall) throw new IOException("simulated widget uninstall failure");
        widget = widgetAvailable();
        return widget;
    }

    static CaptureModuleState captureState(Context context) { return capture; }

    static void enableCapture(Context context) {
        ManagerCalls.add("capture.install");
        capture = new CaptureModuleState("ready", "own");
    }

    static void updateCapture(Context context) {
        ManagerCalls.add("capture.update");
        capture = new CaptureModuleState(capture.enabled() ? "ready" : "disabled", "own");
    }

    static void setCaptureEnabled(Context context, boolean enabled) {
        ManagerCalls.add(enabled ? "capture.enable" : "capture.disable");
        capture = new CaptureModuleState(enabled ? "ready" : "disabled", "own");
    }

    static void uninstallCapture(Context context) throws IOException {
        ManagerCalls.add("capture.uninstall");
        if (failCaptureUninstall) throw new IOException("simulated capture uninstall failure");
        capture = captureAvailable();
    }

    private static Status widgetAvailable() {
        return new Status("available", "present", "own");
    }

    private static CaptureModuleState captureAvailable() {
        return new CaptureModuleState("available", "own");
    }
}
