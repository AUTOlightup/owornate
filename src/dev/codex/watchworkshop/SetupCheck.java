package dev.codex.watchworkshop;

import android.content.Context;
import android.content.Intent;
import java.io.File;
import org.json.JSONObject;

/** One read-only loopback handshake requested by desktop Setup, correlated by nonce. */
final class SetupCheck {
    private SetupCheck() {}

    static void requested(Context context, Intent intent) {
        if (intent == null) return;
        final String token = intent.getStringExtra("owornate_setup_check");
        if (token == null || !token.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) return;
        intent.removeExtra("owornate_setup_check");
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                JSONObject reply = new JSONObject().put("token", token);
                try {
                    RootShell.session(app, 1800, 3500, adb -> {
                        String uid = adb.shell("id -u", 4000).trim();
                        if (adb.exitCode() != 0 || !"0".equals(uid)) {
                            throw new java.io.IOException("本机通道没有 root 权限");
                        }
                        return null;
                    });
                    reply.put("ok", true).put("error", "");
                } catch (Exception e) {
                    reply.put("ok", false).put("error", "本机授权检查失败，请检查 root adbd 与首次授权");
                }
                Payloads.writeAtomically(new File(app.getFilesDir(), "setup-check.json"),
                        reply.toString().getBytes("UTF-8"));
            } catch (Exception e) {
                android.util.Log.e("OWSetup", "Cannot save setup handshake", e);
            }
        }, "workshop-setup-check").start();
    }
}
