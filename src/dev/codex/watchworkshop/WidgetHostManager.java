package dev.codex.watchworkshop;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;

/** Deploys only the signed APK's known shared-host payload over the existing local root channel. */
final class WidgetHostManager {
    /** 接入状态（available / installed / ready）和系统进程白名单结果（present / added / unavailable，未接入时为空）。 */
    static final class Status {
        final String host, keep;
        Status(String host,String keep){this.host=host;this.keep=keep;}
    }
    private static final String SOURCE="/data/user/0/dev.codex.watchworkshop/files/widget-host";
    private static boolean prepared;

    /** 只读状态，已接入时顺带确认白名单：一个连接、一次脚本，通常不到半秒，所以超时也给得短。 */
    static synchronized Status check(Context context)throws Exception {
        String[] lines=run(context,"check",4000,8000).split("\\s+");
        String host=lines[0],keep=lines.length>1?lines[1]:"";
        if(!host.equals("ready")&&!host.equals("installed")&&!host.equals("available"))throw new IOException("未收到有效的接入状态，请重新读取");
        return new Status(host,keep);
    }
    /** 接入或重新接入：会重新加载表盘，等待加载最长约 15 秒。 */
    static synchronized String install(Context context)throws Exception {
        String result=run(context,"install",25000,30000);
        if(!result.equals("ready")&&!result.equals("installed"))throw new IOException("未收到有效的接入状态，请重新读取");
        return result;
    }
    /** 隐藏/显示负一屏的小布助手一行：写系统设置 codex_qc_hide_breeno，hook 下次打开负一屏时读取生效。 */
    static synchronized void breeno(Context context,boolean hide)throws Exception {
        String result=run(context,hide?"breeno-hide":"breeno-show",4000,8000);
        if(!result.equals("hidden")&&!result.equals("shown"))throw new IOException("设置未生效，请重试");
    }
    private static synchronized void prepare(Context context)throws IOException {
        if(prepared)return;
        File dir=new File(context.getFilesDir(),"widget-host");
        if(!dir.getCanonicalPath().equals(new File(SOURCE).getCanonicalPath()))throw new IOException("请在主用户中运行管理器");
        if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("无法准备接入文件");
        for(String name:new String[]{"widget-manage.sh","WatchHide-Launcher-Hook-v11.js"}){
            AtomicFile file=new AtomicFile(new File(dir,name));FileOutputStream out=null;
            try(InputStream in=context.getAssets().open("widget-host/"+name)){
                out=file.startWrite();byte[] buffer=new byte[8192];int n;
                while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
                file.finishWrite(out);out=null;
            }catch(IOException e){if(out!=null)file.failWrite(out);throw e;}
        }
        prepared=true;
    }
    private static String run(Context context,String action,int readMs,long totalMs)throws Exception {
        prepare(context);
        return RootShell.session(context,1800,readMs,adb->{
            String result=adb.shell("sh "+SOURCE+"/widget-manage.sh "+action,totalMs).trim();
            if(adb.exitCode()!=0)throw new IOException(message(result));
            return result;
        });
    }
    private static String message(String result){
        if(result.contains("SHARED_REQUIRED"))return "需要适配的共享运行组件；当前环境暂不支持小组件接入";
        if(result.contains("ROOT_REQUIRED"))return "本机授权未完成，请先完成管理器首次部署";
        if(result.contains("DEVICE_UNSUPPORTED"))return "当前仅适配 OPPO Watch 2 46mm / A.92";
        if(result.contains("SUPERVISOR_REQUIRED"))return "原有启动服务未运行，请先恢复共享运行组件";
        if(result.contains("START_UNCONFIRMED"))return "接入文件已保存，启动未确认；可重新接入恢复，请勿重复添加卡片";
        if(result.contains("BACKUP_MISMATCH"))return "原组件备份不匹配，未替换运行文件";
        return "小组件接入未完成，请检查首次授权或重新读取状态";
    }
}
