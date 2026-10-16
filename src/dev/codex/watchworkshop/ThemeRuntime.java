package dev.codex.watchworkshop;

import android.content.Context;
import android.util.Base64;
import java.io.IOException;
import java.util.UUID;
import org.json.JSONObject;

/** Closed theme protocol; no caller-controlled shell or file paths. */
final class ThemeRuntime {
    private static final String DIR="/data/user_de/0/com.heytap.wearable.launcher/files/watchworkshop-theme";
    private static String shell(AdbClient adb,String command)throws Exception {
        String result=adb.shell(command,15000);
        if(adb.exitCode()!=0)throw new IOException("本机主题通道未完成操作");
        return result;
    }
    /** 单独一次交互：自己开一个连接。 */
    static JSONObject exchange(Context context,String action,ThemeConfig config)throws Exception {
        return RootShell.session(context,1800,12000,adb->exchange(adb,action,config));
    }
    /** 在调用方已经打开的连接上交互；读 hello 和提交请求共用这一个连接。 */
    static synchronized JSONObject exchange(AdbClient adb,String action,ThemeConfig config)throws Exception {
        if(!"read".equals(action)&&!"preview".equals(action)&&!"apply".equals(action)&&!"rollback".equals(action))throw new IllegalArgumentException("Theme action");
        String raw=shell(adb,"test \"$(id -u)\" = 0 && cat "+DIR+"/hello.json && echo; pidof com.heytap.wearable.launcher");
        int end=raw.lastIndexOf('}');if(end<0)throw new IOException("主题组件未加载，请按首次设置安装或检查运行环境");
        JSONObject hello=new JSONObject(raw.substring(0,end+1));
        if(hello.getInt("protocol")!=1||hello.getInt("helperVersion")!=6||!Integer.toString(hello.getInt("pid")).equals(raw.substring(end+1).trim()))throw new IOException("主题接口版本或进程不匹配");
        String session=hello.getString("session"),id=UUID.randomUUID().toString();
        JSONObject request=new JSONObject().put("protocol",1).put("id",id).put("session",session).put("action",action).put("createdAt",System.currentTimeMillis());
        if(config!=null)request.put("config",new JSONObject().put("mode",config.mode).put("seedArgb",config.seedHex()).put("corners",config.corners));
        String encoded=Base64.encodeToString(request.toString().getBytes("UTF-8"),Base64.NO_WRAP);
        String command="test \"$(id -u)\" = 0 && test -d "+DIR+" && printf '%s' '"+encoded+"' | base64 -d > "+DIR+"/request.tmp && "
            +"chown \"$(stat -c '%u:%g' "+DIR+")\" "+DIR+"/request.tmp && chmod 600 "+DIR+"/request.tmp && mv "+DIR+"/request.tmp "+DIR+"/request.json && "
            +"n=0; while [ \"$n\" -lt 100 ]; do if grep -q '"+id+"' "+DIR+"/ack.json 2>/dev/null; then echo __ACK__; cat "+DIR+"/ack.json; echo; exit 0; fi; printf '.'; sleep 0.1; n=$((n+1)); done; exit 1";
        String response=shell(adb,command);int marker=response.indexOf("__ACK__");
        if(marker<0)throw new IOException("等待主题确认超时，请重新读取状态");
        JSONObject ack=new JSONObject(response.substring(marker+7).trim());
        if(ack.getInt("protocol")!=1||!id.equals(ack.getString("id"))||!session.equals(ack.getString("session"))||ack.getInt("pid")!=hello.getInt("pid"))throw new IOException("主题回执身份不匹配");
        if(!ack.getBoolean("ok"))throw new IOException(message(ack.optString("code")));
        JSONObject state=ack.optJSONObject("state");
        if(state==null||!state.optBoolean("ready")||state.optInt("helperVersion")!=6)throw new IOException("主题 helper 未就绪");
        if("apply".equals(action)&&config!=null&&(!config.mode.equals(state.optString("mode"))||config.corners!=state.optBoolean("corners")||("fixed".equals(config.mode)&&color(state.getString("seed"))!=config.seed)))throw new IOException("主题实际配置未匹配，请重新读取");
        return ack;
    }
    static int color(String hex){if(hex==null||!hex.matches("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"))throw new IllegalArgumentException("主题组件返回的配色格式无效");return (int)Long.parseLong(hex.substring(1),16)|0xff000000;}
    private static String message(String code){
        if("SESSION_CHANGED".equals(code))return "Launcher 已重载，请重新读取后操作";
        if("SAMPLE_FAILED".equals(code))return "暂时无法读取当前表盘配色，原设置保留";
        if("EXPIRED".equals(code))return "请求已过期，请重新操作";
        if("BUSY".equals(code))return "主题正在处理，请稍后重试";
        return "主题操作未确认，请重新读取或恢复上一次设置";
    }
}
