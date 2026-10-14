package dev.codex.watchworkshop;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;
import java.io.*;
import org.json.JSONObject;

/** On-watch lifecycle of the Monet payload shipped in this signed APK. */
final class ModuleManager {
    static final class State {
        public final String kind, message;
        public final boolean active;
        public final boolean activeKnown;
        State(String kind, boolean active, String message) { this(kind,active,message,true); }
        State(String kind, boolean active, String message,boolean activeKnown) { this.kind=kind;this.active=active;this.message=message;this.activeKnown=activeKnown; }
        boolean installed(){return "installed".equals(kind)||"shared".equals(kind);}
        boolean removable(){return "installed".equals(kind);}
    }
    private static final String SOURCE="/data/user/0/dev.codex.watchworkshop/files/monet";
    private static boolean prepared;
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
    private static String command(Context context,String action)throws Exception {
        if(!"status".equals(action)&&!"install".equals(action)&&!"enable".equals(action)&&!"disable".equals(action)&&!"uninstall".equals(action))throw new IllegalArgumentException("module action");
        prepare(context);
        RsaKeyStore keys=new RsaKeyStore(new File(context.getFilesDir(),"adb"));
        try(AdbClient adb=AdbClient.open(1800,12000,keys.privateKey(),keys.publicKeyLine())){
            String output=adb.shell("sh "+SOURCE+"/module-manage.sh "+action,20000).trim();
            if(adb.exitCode()!=0)throw new IOException(message(output));
            return output;
        }
    }
    static synchronized State read(Context context)throws Exception {
        String kind;
        try{kind=command(context,"status");}
        catch(java.net.ConnectException|java.net.SocketTimeoutException e){return new State("setup",false,"管理器未连接，请检查首次部署");}
        if("installed".equals(kind)||"shared".equals(kind)){
            try{
                JSONObject state=ThemeRuntime.exchange(context,"read",null).getJSONObject("state");
                boolean neutral="original".equals(state.optString("mode"))&&!state.optBoolean("corners");
                boolean active=!"shared".equals(kind)||!neutral||context.getSharedPreferences("modules",0).getBoolean("monetEnabled",!neutral);
                return new State(kind,active,active?"已启用":"已停用");
            }catch(Exception e){return new State(kind,false,"运行状态未确认，请重新读取或启用",false);}
        }
        if("disabled".equals(kind))return new State("installed",false,"已停用");
        if("available".equals(kind))return new State(kind,false,"未安装 · 可直接在手表安装");
        if("setup".equals(kind))return new State(kind,false,"首次使用需要部署管理器");
        return new State("conflict",false,"已有组件版本不兼容");
    }
    static synchronized State install(Context context)throws Exception {
        State state=read(context);
        if(!"available".equals(state.kind))throw new IOException("当前状态无法安装，请刷新首页");
        command(context,"install");
        return setEnabled(context,true);
    }
    static synchronized State setEnabled(Context context,boolean enabled)throws Exception {
        State before=read(context);
        if(!before.installed())throw new IOException("请先安装莫奈取色");
        if("shared".equals(before.kind)){
            SharedPreferences prefs=context.getSharedPreferences("modules",0);
            JSONObject current=ThemeRuntime.exchange(context,"read",null).getJSONObject("state");
            ThemeConfig config;
            if(enabled){
                String saved=prefs.getString("monetBeforeDisable","");
                if(saved.isEmpty())config=new ThemeConfig("auto",0xffebc16c,true);
                else{JSONObject old=new JSONObject(saved);config=new ThemeConfig(old.getString("mode"),ThemeRuntime.color(old.getString("seed")),old.getBoolean("corners"));}
            }else{
                if(before.active&&!prefs.edit().putString("monetBeforeDisable",new JSONObject().put("mode",current.getString("mode")).put("seed",current.getString("seed")).put("corners",current.getBoolean("corners")).toString()).commit())throw new IOException("停用前设置未保存，请重试");
                config=new ThemeConfig("original",0xffebc16c,false);
            }
            ThemeRuntime.exchange(context,"apply",config);
            if(!prefs.edit().putBoolean("monetEnabled",enabled).commit())throw new IOException("启停结果已返回，但本地状态保存失败，请重新读取");
        }else{
            command(context,enabled?"enable":"disable");
            if(enabled){
                Exception last=null;
                for(int i=0;i<12;i++){
                    try{ThemeRuntime.exchange(context,"read",null);last=null;break;}
                    catch(Exception e){last=e;Thread.sleep(1000);}
                }
                if(last!=null){command(context,"disable");throw new IOException("组件已安装，启动未确认。请点启用重试",last);}
            }
        }
        State after=read(context);
        if(!after.activeKnown||after.active!=enabled)throw new IOException("操作结果尚未确认，请刷新后重试");
        return after;
    }
    static void rememberThemeEnabled(Context context)throws IOException {
        if(!context.getSharedPreferences("modules",0).edit().putBoolean("monetEnabled",true).commit())throw new IOException("主题已生效，但启用状态保存失败，请重新读取");
    }
    static synchronized State uninstall(Context context)throws Exception {
        if(!read(context).removable())throw new IOException("现有共享组件不能单独卸载，可停用莫奈");
        command(context,"uninstall");
        State after=read(context);
        if(!"available".equals(after.kind))throw new IOException("卸载未确认，请刷新状态");
        return after;
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
