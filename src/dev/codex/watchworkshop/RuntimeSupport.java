package dev.codex.watchworkshop;

import android.content.Context;
import java.util.concurrent.atomic.AtomicBoolean;

/** Finite startup recovery for this package's standalone installation only. */
final class RuntimeSupport {
    private static final AtomicBoolean RUNNING=new AtomicBoolean();
    private static final String COMMAND="test \"$(id -u)\" = 0 || exit 1; "
        +"test -f /data/local/tmp/watchworkshop-runtime/installed || exit 0; "
        +"test ! -f /data/local/tmp/watchworkshop-runtime/disabled || exit 0; "
        +"test ! -f /data/local/tmp/watchhide/hook.js || exit 0; "
        +"test ! -f /data/local/tmp/watch-monet/hook.js || exit 0; "
        +"printf '__BOOT__'; cat /proc/sys/kernel/random/boot_id; "
        +"sh /data/local/tmp/watchworkshop-runtime/monet-service.sh ensure";
    static void ensureAsync(Context context,boolean boot){final Context app=context.getApplicationContext();new Thread(()->ensure(app,boot),"workshop-startup").start();}
    static void ensure(Context context,boolean boot){
        if(!RUNNING.compareAndSet(false,true))return;
        try{RootShell.session(context,1200,4500,adb->{
                String raw=adb.shell(COMMAND,7500);int rc=adb.exitCode();
                android.content.SharedPreferences.Editor e=context.getSharedPreferences("runtime",0).edit().putLong("time",System.currentTimeMillis()).putString("result",rc==0?"启动检查完成":"启动未完成，请重新检查");
                if(boot){int start=raw.indexOf("__BOOT__");if(start>=0){String id=raw.substring(start+8).split("[\\r\\n]",2)[0].trim();if(id.matches("[a-fA-F0-9-]{36}"))e.putString("bootId",id).putBoolean("bootOk",rc==0);}}
                return e.commit();
            });
        }catch(Exception e){context.getSharedPreferences("runtime",0).edit().putString("result","启动未完成，请检查本机授权").apply();}
        finally{RUNNING.set(false);}
    }
    static String lastResult(Context context){return context.getSharedPreferences("runtime",0).getString("result","尚无独立启动记录");}
}
