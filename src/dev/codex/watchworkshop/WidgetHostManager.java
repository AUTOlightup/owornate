package dev.codex.watchworkshop;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;

/** Deploys only the signed APK's known shared-host payload over the existing local root channel. */
final class WidgetHostManager {
    /** 接入状态（available / installed / ready）和系统进程白名单结果（present / missing / unavailable）。 */
    static final class Status {
        final String host, keep;
        Status(String host,String keep){this.host=host;this.keep=keep;}
    }
    private static final String SOURCE="/data/user/0/dev.codex.watchworkshop/files/widget-host";
    private static boolean prepared;

    /** 只读状态；成功或失败都由 Manager 写入共享缓存，不依赖 Activity 存活。 */
    static synchronized Status check(Context context)throws Exception {
        try {
            Status status=read(context,"check",4000,8000);
            RuntimeStateStore.recordWidgets(context,status);
            return status;
        } catch(Exception e) {
            RuntimeStateStore.failWidgets(context,e);
            throw e;
        }
    }

    /** 接入/重新接入并在同一固定流程里保活；keepalive 失败不撤销已确认的接入。 */
    static synchronized Status install(Context context)throws Exception {
        try {
            Status status=read(context,"install",25000,30000);
            RuntimeStateStore.recordWidgets(context,status);
            return status;
        } catch(Exception operation) {
            readAfterFailure(context);
            throw operation;
        }
    }

    /** 只修复开机后台白名单，不重载 Launcher。 */
    static synchronized Status repairBackground(Context context)throws Exception {
        try {
            Status status=read(context,"repair",4000,8000);
            RuntimeStateStore.recordWidgets(context,status);
            return status;
        } catch(Exception operation) {
            readAfterFailure(context);
            throw operation;
        }
    }

    static synchronized CaptureModuleState captureState(Context context)throws Exception {
        try {
            CaptureModuleState state=new CaptureModuleState(run(context,"capture-status",4000,8000));
            RuntimeStateStore.recordCapture(context,state);
            return state;
        }catch(Exception e){RuntimeStateStore.failCapture(context,e);throw e;}
    }

    /** Explicit Hook update; unlike widget installation this does not change the boot whitelist. */
    static synchronized void enableCapture(Context context)throws Exception {
        try {
            RuntimeStateStore.recordWidgets(context,read(context,"capture-install",25000,30000));
            captureState(context);
        }catch(Exception e){readAfterFailure(context);try{captureState(context);}catch(Exception ignored){}throw e;}
    }

    static synchronized void setCaptureEnabled(Context context,boolean enabled)throws Exception {
        // Serialize the stop + module switch with every new capture request.
        synchronized(CaptureManager.class){
            if(!enabled){
                CaptureManager.Status result=CaptureManager.stop(context);
                if(result.active())throw new IOException("录屏正在保存，完成后再停用模块");
            }
            String result=run(context,enabled?"capture-enable":"capture-disable",4000,8000);
            if(!(enabled?"enabled":"disabled").equals(result))throw new IOException("模块开关未确认，请重试");
            captureState(context);
        }
    }

    /** 隐藏/显示负一屏的小布助手一行：写系统设置 codex_qc_hide_breeno，hook 下次打开负一屏时读取生效。 */
    static synchronized void breeno(Context context,boolean hide)throws Exception {
        String result=run(context,hide?"breeno-hide":"breeno-show",4000,8000);
        if(!result.equals("hidden")&&!result.equals("shown"))throw new IOException("设置未生效，请重试");
    }

    private static Status read(Context context,String action,int readMs,long totalMs)throws Exception {
        String result=run(context,action,readMs,totalMs);
        String[] lines=result.split("\\r?\\n");
        if(lines.length<2)throw new IOException("未收到完整的接入状态，请重新读取");
        String host=lines[0].trim(),keep=lines[1].trim();
        boolean added="added".equals(keep)&&("install".equals(action)||"repair".equals(action));
        if(!oneOf(host,"available","installed","ready")
                ||(!oneOf(keep,"present","missing","unavailable")&&!added))
            throw new IOException("未收到完整的接入状态，请重新读取");
        if(added) context.getSharedPreferences("workshop",0).edit()
                .putLong("widgetKeepBoot",System.currentTimeMillis()-android.os.SystemClock.elapsedRealtime()).apply();
        return new Status(host,keep);
    }

    private static boolean oneOf(String value,String... allowed) {
        for(String item:allowed)if(item.equals(value))return true;
        return false;
    }

    /** One read-only pass after a failed write. The original operation error still reaches the UI. */
    private static void readAfterFailure(Context context) {
        try {
            Status status=read(context,"check",4000,8000);
            RuntimeStateStore.recordWidgets(context,status);
        } catch(Exception read) {
            try { RuntimeStateStore.failWidgets(context,read); } catch(Exception ignored) { }
        }
    }

    private static synchronized void prepare(Context context)throws IOException {
        if(prepared)return;
        File dir=new File(context.getFilesDir(),"widget-host");
        if(!dir.getCanonicalPath().equals(new File(SOURCE).getCanonicalPath()))throw new IOException("请在主用户中运行管理器");
        if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("无法准备接入文件");
        for(String name:new String[]{"widget-manage.sh","WatchHide-Launcher-Hook-v12.js"}){
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
            if(adb.exitCode()!=0)throw new IOException(action.startsWith("capture")&&result.contains("START_UNCONFIRMED")
                    ?"模块文件已保存，启动未确认；请重新接入恢复":message(result));
            return result;
        });
    }

    private static String message(String result){
        if(result.contains("SHARED_REQUIRED"))return "需要适配的共享运行组件；当前环境暂不支持接入";
        if(result.contains("ROOT_REQUIRED"))return "本机授权未完成，请先完成管理器首次部署";
        if(result.contains("DEVICE_UNSUPPORTED"))return "当前仅适配 OPPO Watch 2 46mm / A.92";
        if(result.contains("SUPERVISOR_REQUIRED"))return "原有启动服务未运行，请先恢复共享运行组件";
        if(result.contains("START_UNCONFIRMED"))return "接入文件已保存，启动未确认；可重新接入恢复，请勿重复添加卡片";
        if(result.contains("BACKUP_MISMATCH"))return "原组件备份不匹配，未替换运行文件";
        if(result.contains("KEEP_UNAVAILABLE"))return "后台保活修复未完成，请检查系统权限后重试";
        if(result.contains("CAPTURE_SETTING_FAILED"))return "截屏录屏模块开关未保存，请重试";
        return "接入未完成，请检查首次授权或重新读取状态";
    }
}

