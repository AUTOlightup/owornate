package dev.codex.watchworkshop;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机和本应用升级后，在后台线程做一次独立组件的有限启动恢复（见 {@link RuntimeSupport}）。 */
public final class RuntimeBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction());
        if (!boot && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) return;
        final PendingResult pending = goAsync();
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                RuntimeSupport.ensure(app, boot);
            } finally {
                pending.finish();
            }
        }, "workshop-boot").start();
    }
}
