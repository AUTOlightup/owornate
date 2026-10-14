package dev.codex.watchworkshop;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class RuntimeBootReceiver extends BroadcastReceiver {
    public void onReceive(Context context,Intent intent){
        if(intent==null||(!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())&&!Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())))return;
        final PendingResult pending=goAsync();final Context app=context.getApplicationContext();
        new Thread(()->{try{RuntimeSupport.ensure(app,Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()));}finally{pending.finish();}},"workshop-boot").start();
    }
}
