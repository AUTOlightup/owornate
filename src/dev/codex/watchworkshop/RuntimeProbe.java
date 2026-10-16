package dev.codex.watchworkshop;

import android.content.Context;
import org.json.JSONObject;

/** Only fixed, read-only probes. No caller-supplied commands or paths. */
final class RuntimeProbe {
    static final String LAUNCHER="com.heytap.wearable.launcher";
    static final String HOOK_SHA="6e46e44d3b4f5fc2162b60dd90b5de2b3c2e4f4cfcf50d87eee5c5071883f722";
    static final String THEME_SHA="706205ab96a67bfca062bca5152f89fe7cfaddc5ed9db2fe121d1697723dca74";
    private static final String COMMAND="id -u; echo __BUILD__; getprop ro.build.display.id; "
        +"echo __HOOK__; sha256sum /data/local/tmp/watchhide/hook.js 2>/dev/null; "
        +"echo __THEME_HASH__; if [ -f /data/local/tmp/watchface-theme/theme.jar ]; then sha256sum /data/local/tmp/watchface-theme/theme.jar; else sha256sum /data/local/tmp/watchworkshop-runtime/theme.jar 2>/dev/null; fi; "
        +"echo __THEME_STATUS__; cat /data/user_de/0/com.heytap.wearable.launcher/files/watchface-theme-status.json 2>/dev/null; "
        +"echo; echo __GUARD__; cat /data/local/tmp/wearqq-powerguard/status 2>/dev/null; "
        +"echo __BLUE__; test -f /data/local/tmp/wearqq-blue-ui/blue-point.jar && echo present; "
        +"echo __END__; true";
    private static String section(String s,String a,String b){int i=s.indexOf(a),j=s.indexOf(b,i+a.length());return i<0||j<0?"":s.substring(i+a.length(),j).trim();}
    static JSONObject read(Context context) throws Exception {
        return RootShell.session(context,1800,3500,adb->{
            String raw=adb.shell(COMMAND,14000);
            if(adb.exitCode()!=0||!raw.contains("__END__"))throw new java.io.IOException("设备检测未完成");
            if(!raw.substring(0,raw.indexOf("__BUILD__")).trim().equals("0"))throw new java.io.IOException("本机通道没有 root 权限");
            JSONObject r=new JSONObject();r.put("checkedAt",System.currentTimeMillis());r.put("root",true);
            r.put("build",section(raw,"__BUILD__","__HOOK__"));
            r.put("hookSha",section(raw,"__HOOK__","__THEME_HASH__").split("\\s+")[0]);
            r.put("themeSha",section(raw,"__THEME_HASH__","__THEME_STATUS__").split("\\s+")[0]);
            String theme=section(raw,"__THEME_STATUS__","__GUARD__");
            try{JSONObject source=new JSONObject(theme),safe=new JSONObject();for(String k:new String[]{"ready","mode","seed","active","onActive","off","onOff","helperVersion","corners","error"})if(source.has(k))safe.put(k,source.get(k));r.put("theme",safe);}catch(Exception ignored){r.put("theme",JSONObject.NULL);}
            JSONObject guard=new JSONObject();for(String line:section(raw,"__GUARD__","__BLUE__").split("[\\r\\n]+")){int eq=line.indexOf('=');if(eq>0)guard.put(line.substring(0,eq),line.substring(eq+1));}
            r.put("guard",guard);r.put("bluePresent",section(raw,"__BLUE__","__END__").equals("present"));return r;
        });
    }
    static boolean hasTheme(JSONObject r){return r!=null&&!r.optString("themeSha").isEmpty();}
    static boolean knownTheme(JSONObject r){return r!=null&&(THEME_SHA.equals(r.optString("themeSha"))||"715a624b78d1ede21e81a2bfb93b44eb254dbdf77db2eefd26741f745f9851cd".equals(r.optString("themeSha")));}
    static boolean knownHook(JSONObject r){return r!=null&&(HOOK_SHA.equals(r.optString("hookSha"))||"0a5654f86c5fa2570d74f9877ff9abe974dcb82ba71c66869b4607f09182c07d".equals(r.optString("hookSha"))||"9b8116a64219e919e0f76777f9bbefb4f42821e651a337d109a21a3a457823f1".equals(r.optString("hookSha")));}
}
