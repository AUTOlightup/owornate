package dev.codex.watchworkshop;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import org.json.JSONObject;

/** Native screencap/screenrecord only. The root worker is the recording authority. */
final class CaptureManager {
    private static final String SOURCE="/data/user/0/dev.codex.watchworkshop/files/capture/capture.sh";
    private static boolean prepared;

    static final class Status {
        final String phase, token, path, error;
        final long startedAt;
        Status(JSONObject json) {
            phase=json.optString("phase","error"); token=json.optString("token","");
            path=json.optString("path",""); startedAt=json.optLong("startedAt");
            error=message(json.optString("error",""));
        }
        boolean active(){return "starting".equals(phase)||"recording".equals(phase)||"stopping".equals(phase);}
    }

    static SharedPreferences preferences(Context context){return context.getSharedPreferences("capture_runtime",0);}

    static synchronized Status read(Context context)throws Exception {return run(context,"refresh",10000,13000);}
    static synchronized Status screenshot(Context context)throws Exception {return run(context,"screenshot",12000,16000);}
    static synchronized Status start(Context context,int bitrate)throws Exception {
        if(bitrate!=600000&&bitrate!=1200000&&bitrate!=2400000)throw new IllegalArgumentException("bitrate");
        return run(context,"start "+bitrate+" "+UUID.randomUUID().toString().replace("-",""),12000,15000);
    }
    static synchronized Status stop(Context context)throws Exception {return run(context,"stop",12000,16000);}
    static Status awaitEnd(Context context,String token)throws Exception {
        if(!token.matches("[a-f0-9]{32}"))throw new IllegalArgumentException("session");
        return run(context,"wait "+token,200000,195000,false);
    }

    static void fail(Context context,Exception error){
        // Retain the last observed recording/path; a connection error isn't proof it stopped.
        preferences(context).edit().putString("error",WatchActivity.message(error)).apply();
    }

    private static Status run(Context context,String action,int readMs,long totalMs)throws Exception {
        return run(context,action,readMs,totalMs,true);
    }
    static void accept(Context context,Status status){
        if(!"screenshot".equals(status.phase))preferences(context).edit()
                .putString("phase",status.phase).putString("token",status.token)
                .putString("path",status.path).putLong("startedAt",status.startedAt)
                .putString("error",status.error).apply();
    }
    private static Status run(Context context,String action,int readMs,long totalMs,boolean publish)throws Exception {
        prepare(context);
        try {
            Status status=RootShell.session(context,1800,readMs,adb->{
                String result=adb.shell("sh "+SOURCE+" "+action,totalMs).trim();
                if(adb.exitCode()!=0)throw new IOException(message(result));
                JSONObject json=new JSONObject(result);
                String phase=json.optString("phase");
                if(!"idle".equals(phase)&&!"starting".equals(phase)&&!"recording".equals(phase)
                        &&!"stopping".equals(phase)&&!"error".equals(phase)&&!"screenshot".equals(phase))
                    throw new IOException("未收到有效的录制状态，请重试");
                return new Status(json);
            });
            if(publish)accept(context,status);
            return status;
        }catch(Exception error){if(publish)fail(context,error);throw error;}
    }

    private static synchronized void prepare(Context context)throws IOException {
        if(prepared)return;
        File dest=new File(context.getFilesDir(),"capture/capture.sh");
        if(!new File(SOURCE).getCanonicalPath().equals(dest.getCanonicalPath()))throw new IOException("请在手表主用户中使用截屏与录屏");
        if(!dest.getParentFile().isDirectory()&&!dest.getParentFile().mkdirs())throw new IOException("无法准备录制文件");
        AtomicFile file=new AtomicFile(dest);FileOutputStream out=null;
        try(InputStream in=context.getAssets().open("capture/capture.sh")){
            out=file.startWrite();byte[] buffer=new byte[8192];int n;
            while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
            file.finishWrite(out);out=null;prepared=true;
        }finally{if(out!=null)file.failWrite(out);}
    }

    private static String message(String code){
        if(code==null||code.isEmpty())return "";
        if(code.contains("ROOT_REQUIRED"))return "本机授权未完成，请先完成首次部署";
        if(code.contains("MODULE_DISABLED"))return "截屏录屏模块已停用，请先在 OWOrnate 启用";
        if(code.contains("DEVICE_UNSUPPORTED"))return "当前仅适配 OPPO Watch 2 / A.92";
        if(code.contains("CAPTURE_BUSY"))return "录制正在启动或保存，请稍候再试";
        if(code.contains("STORAGE_LOW"))return "剩余空间不足，请清理空间或选择较低码率";
        if(code.contains("STORAGE_UNAVAILABLE"))return "无法访问保存目录，请检查存储状态";
        if(code.contains("STATE_WRITE_FAILED"))return "录制状态未保存，请检查剩余空间后重试";
        if(code.contains("SCREENSHOT_FAILED"))return "截屏失败，请重试";
        if(code.contains("RECORDING_FAILED")||code.contains("START_FAILED"))return "录制未完成，请检查剩余空间后重试";
        if(code.contains("INTERRUPTED"))return "录制进程已退出，文件保存状态未确认";
        if(code.contains("STOP_FAILED"))return "停止未确认，请重新打开控制中心重试";
        return code;
    }
}
