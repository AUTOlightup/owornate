package dev.codex.watchworkshop;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;
import java.io.*;
import org.json.JSONObject;

/**
 * On-watch lifecycle of the Monet payload shipped in this signed APK.
 *
 * <p>每个公开操作只开一个本机 ADB 连接：状态脚本、主题读取和写入都在这个连接上依次执行。
 */
final class ModuleManager {
    private static final String SOURCE="/data/user/0/dev.codex.watchworkshop/files/monet";
    private static boolean prepared;

    /** 把 APK 里的组件文件放到私有目录，供 root 脚本读取；每个进程只做一次。 */
    private static synchronized void prepare(Context context)throws IOException {
        if(prepared)return;
        File dir=new File(context.getFilesDir(),"monet");
        if(!dir.getCanonicalPath().equals(new File(SOURCE).getCanonicalPath()))throw new IOException("请在手表主用户中运行管理器");
        if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("无法准备安装文件");
        for(String name:new String[]{"theme.jar","hook.js","monet-service.sh","module-manage.sh"}){
            AtomicFile dest=new AtomicFile(new File(dir,name));FileOutputStream out=null;
            try(InputStream in=context.getAssets().open("monet/"+name)){
                out=dest.startWrite();byte[] b=new byte[8192];int n;
                while((n=in.read(b))!=-1)out.write(b,0,n);
                dest.finishWrite(out);out=null;
            }catch(IOException e){if(out!=null)dest.failWrite(out);throw e;}
        }
        prepared=true;
    }
    private static <T> T session(Context context,RootShell.Body<T> body)throws Exception {
        prepare(context);
        return RootShell.session(context,1800,12000,body);
    }
    private static String command(AdbClient adb,String action)throws Exception {
        if(!"status".equals(action)&&!"install".equals(action)&&!"enable".equals(action)&&!"disable".equals(action)&&!"uninstall".equals(action))throw new IllegalArgumentException("module action");
        String output=adb.shell("sh "+SOURCE+"/module-manage.sh "+action,20000).trim();
        if(adb.exitCode()!=0)throw new IOException(message(output));
        return output;
    }

    static synchronized ModuleState read(Context context)throws Exception {
        ModuleState state;
        try{
            state=session(context,adb->readCurrent(context,adb));
        }catch(Exception e){RuntimeStateStore.failMonet(context,e);throw e;}
        if(state.installed()&&!state.activeKnown){
            IOException error=new IOException(state.message);
            RuntimeStateStore.failMonet(context,error,state);
            throw error;
        }
        RuntimeStateStore.recordMonet(context,state);
        return state;
    }
    private static ModuleState readCurrent(Context context,AdbClient adb)throws Exception {
        String kind=command(adb,"status");
        if("installed".equals(kind)||"shared".equals(kind)){
            try{
                JSONObject state=ThemeRuntime.exchange(adb,"read",null).getJSONObject("state");
                boolean neutral="original".equals(state.optString("mode"))&&!state.optBoolean("corners");
                boolean active=!"shared".equals(kind)||!neutral||context.getSharedPreferences("modules",0).getBoolean("monetEnabled",!neutral);
                ThemeConfig theme=new ThemeConfig(state.getString("mode"),ThemeRuntime.color(state.getString("seed")),state.getBoolean("corners"));
                return new ModuleState(kind,active,active?"已启用":"已停用",true,theme);
            }catch(Exception e){return new ModuleState(kind,false,"组件已安装，运行状态读取失败，请重试",false);}
        }
        if("disabled".equals(kind))return new ModuleState("installed",false,"已停用");
        if("available".equals(kind))return new ModuleState(kind,false,"未安装 · 可直接在手表安装");
        if("setup".equals(kind))return new ModuleState(kind,false,"首次使用需要部署管理器");
        if("conflict".equals(kind))return new ModuleState(kind,false,"已有组件版本不兼容");
        throw new IOException("未收到有效的莫奈状态，请重新读取");
    }
    static synchronized ModuleState install(Context context)throws Exception {
        try{
        session(context,adb->{
            if(!"available".equals(readCurrent(context,adb).kind))throw new IOException("当前状态无法安装，请刷新首页");
            return command(adb,"install");
        });
        return setEnabled(context,true);
        }catch(Exception e){RuntimeStateStore.failMonet(context,e);throw e;}
    }
    static synchronized ModuleState setEnabled(Context context,boolean enabled)throws Exception {
        try{
        ModuleState after=session(context,adb->{
            ModuleState before=readCurrent(context,adb);
            if(!before.installed())throw new IOException("请先安装莫奈取色");
            if("shared".equals(before.kind)){
                SharedPreferences prefs=context.getSharedPreferences("modules",0);
                JSONObject current=ThemeRuntime.exchange(adb,"read",null).getJSONObject("state");
                ThemeConfig config;
                if(enabled){
                    String saved=prefs.getString("monetBeforeDisable","");
                    if(saved.isEmpty())config=new ThemeConfig("auto",WorkshopUi.ACCENT,true);
                    else{JSONObject old=new JSONObject(saved);config=new ThemeConfig(old.getString("mode"),ThemeRuntime.color(old.getString("seed")),old.getBoolean("corners"));}
                }else{
                    if(before.active&&!prefs.edit().putString("monetBeforeDisable",new JSONObject().put("mode",current.getString("mode")).put("seed",current.getString("seed")).put("corners",current.getBoolean("corners")).toString()).commit())throw new IOException("停用前设置未保存，请重试");
                    config=new ThemeConfig("original",WorkshopUi.ACCENT,false);
                }
                ThemeRuntime.exchange(adb,"apply",config);
                if(!prefs.edit().putBoolean("monetEnabled",enabled).commit())throw new IOException("启停结果已返回，但本地状态保存失败，请重新读取");
            }else{
                command(adb,enabled?"enable":"disable");
                if(enabled){
                    Exception last=null;
                    for(int i=0;i<12;i++){
                        try{ThemeRuntime.exchange(adb,"read",null);last=null;break;}
                        catch(Exception e){last=e;Thread.sleep(1000);}
                    }
                    if(last!=null){command(adb,"disable");throw new IOException("组件已安装，启动未确认。请点启用重试",last);}
                }
            }
            return readCurrent(context,adb);
        });
        if(!after.activeKnown||after.active!=enabled)throw new IOException("操作结果尚未确认，请刷新后重试");
        RuntimeStateStore.recordMonet(context,after);
        return after;
        }catch(Exception e){RuntimeStateStore.failMonet(context,e);throw e;}
    }
    static void rememberThemeEnabled(Context context)throws IOException {
        if(!context.getSharedPreferences("modules",0).edit().putBoolean("monetEnabled",true).commit())throw new IOException("主题已生效，但启用状态保存失败，请重新读取");
    }
    static synchronized ModuleState uninstall(Context context)throws Exception {
        try{
        ModuleState after=session(context,adb->{
            if(!readCurrent(context,adb).removable())throw new IOException("现有共享组件不能单独卸载，可停用莫奈");
            command(adb,"uninstall");
            return readCurrent(context,adb);
        });
        if(!"available".equals(after.kind))throw new IOException("卸载未确认，请刷新状态");
        RuntimeStateStore.recordMonet(context,after);
        return after;
        }catch(Exception e){RuntimeStateStore.failMonet(context,e);throw e;}
    }
    private static String message(String code){
        if(code.contains("SETUP_REQUIRED")||code.contains("ROOT_REQUIRED"))return "首次部署未完成，请用电脑部署管理器一次";
        if(code.contains("DEVICE_UNSUPPORTED"))return "当前仅适配 OPPO Watch 2 46mm / A.92";
        if(code.contains("SHARED_RUNTIME"))return "现有共享组件不能单独安装或卸载";
        if(code.contains("UNKNOWN_FILES"))return "发现其他组件文件，安装已停止";
        if(code.contains("FOREIGN_RUNTIME"))return "表盘已有其他运行组件，无法安装第二套莫奈";
        if(code.contains("PAYLOAD_INVALID"))return "安装文件不完整，请重新安装管理器 APK";
        if(code.contains("START_FAILED"))return "组件已安装，启动失败。请点启用重试";
        return "功能操作未完成，请刷新后重试（"+code.replace('\n',' ').substring(0,Math.min(code.length(),100))+"）";
    }
}
