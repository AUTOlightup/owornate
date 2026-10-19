package dev.codex.watchworkshop;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.widget.Toast;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Foreground only during an explicit capture. No boot recovery or automatic recording. */
public final class CaptureService extends Service {
    private static final String CHANNEL="capture";
    private static final int NOTIFICATION=41;
    private static final ExecutorService IO=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile boolean destroyed;
    private volatile String observing="";
    private boolean stopping;
    private int currentRequest;
    private int latestStartId;
    private PowerManager.WakeLock screen;

    static void request(Context context,String action){
        Intent intent=new Intent(context,CaptureService.class).putExtra("action",action);
        context.startForegroundService(intent);
    }
    static void startRecording(Context context,int bitrate){
        context.startForegroundService(new Intent(context,CaptureService.class)
                .putExtra("action","start").putExtra("bitrate",bitrate));
    }
    /** Reconcile the native session on the same queue as start/stop, without starting a service. */
    static void refreshStatus(Context context,java.util.function.Consumer<Exception> finished){
        final Context app=context.getApplicationContext();
        IO.execute(()->{
            Exception problem=null;
            try{CaptureManager.read(app);}catch(Exception e){CaptureManager.fail(app,e);problem=e;}
            final Exception result=problem;
            new Handler(Looper.getMainLooper()).post(()->finished.accept(result));
        });
    }
    @Override public void onCreate(){
        super.onCreate();
        NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,"截屏与录屏",NotificationManager.IMPORTANCE_LOW));
        startForeground(NOTIFICATION,notification("正在准备…",false));
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        latestStartId=startId;
        // A reused service must satisfy each startForegroundService request too.
        startForeground(NOTIFICATION,notification("正在处理…",!observing.isEmpty()));
        if(intent==null){finishService();return START_NOT_STICKY;}
        final String action=intent.getStringExtra("action");
        final int bitrate=intent.getIntExtra("bitrate",1200000);
        Runnable execute=()->IO.execute(()->{currentRequest=startId;perform(action,bitrate);});
        // Screenshots wait for the transparent entry to leave. Recording starts
        // already wait in the options page's onStop; stop needs no window delay.
        if("screenshot".equals(action))main.postDelayed(execute,200);
        else execute.run();
        return START_NOT_STICKY;
    }
    private void perform(String action,int bitrate){
        if(destroyed)return;
        try {
            if("screenshot".equals(action)){
                CaptureManager.Status result=CaptureManager.screenshot(this);
                toast("截屏已保存："+result.path);
                CaptureManager.Status current=CaptureManager.read(this);
                if(current.active())observe(current);else finishService();
            }else if("record".equals(action)){
                CaptureManager.Status current=CaptureManager.read(this);
                if(current.active())stopRecording();
                else {
                    startActivity(new Intent(this,CaptureOptionsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            |Intent.FLAG_ACTIVITY_MULTIPLE_TASK|Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS|Intent.FLAG_ACTIVITY_NO_ANIMATION));
                    finishService();
                }
            }else if("start".equals(action)){
                CaptureManager.Status current=CaptureManager.read(this);
                if(current.active()){observe(current);return;}
                CaptureManager.Status result=CaptureManager.start(this,bitrate);
                if(!result.active())throw new java.io.IOException(result.error.isEmpty()?"录制启动未确认":result.error);
                observe(result);
            }else if("stop".equals(action))stopRecording();
            else finishService();
        }catch(Exception error){
            CaptureManager.fail(this,error);toast(WatchActivity.message(error));
            // A failed command doesn't establish that an existing recording ended.
            try {
                CaptureManager.Status current=CaptureManager.read(this);
                CaptureManager.fail(this,error);
                if(current.active()){observe(current);return;}
            }
            catch(Exception ignored){}
            finishService();
        }
    }
    private void stopRecording()throws Exception {
        stopping=true;show("正在保存录屏…",false);
        CaptureManager.Status result=CaptureManager.stop(this);
        if(result.active()){observe(result);return;}
        complete(result);
    }
    private void observe(CaptureManager.Status state){
        keepScreenOn();
        show("stopping".equals(state.phase)?"正在保存录屏…":
                "starting".equals(state.phase)?"正在准备录屏…":"录屏中 · 单段最长 3 分钟",true);
        if(state.token.equals(observing))return;
        observing=state.token;
        final String token=state.token;
        // One blocking read on a separate ADB connection; stop commands remain independent.
        new Thread(()->{
            try {
                CaptureManager.Status result=CaptureManager.awaitEnd(getApplicationContext(),token);
                IO.execute(()->{if(!destroyed&&token.equals(observing)){
                    CaptureManager.accept(this,result);
                    if(result.active())show("状态读取未完成，请点停止重试",true);else complete(result);
                }});
            }catch(Exception error){if(!destroyed&&token.equals(observing))show("录制状态读取失败，可点停止重试",true);}
        },"capture-completion").start();
    }
    private void complete(CaptureManager.Status state){
        if(!state.error.isEmpty())toast(state.error);
        else if(!state.path.isEmpty())toast("录屏已保存："+state.path);
        else if(stopping)toast("当前没有正在录制的视频");
        finishService();
    }
    @SuppressWarnings("deprecation")
    private void keepScreenOn(){
        if(screen==null){
            screen=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK,"OWOrnate:capture");
            screen.setReferenceCounted(false);
        }
        if(!screen.isHeld())screen.acquire(190000);
    }
    private Notification notification(String text,boolean allowStop){
        Notification.Builder builder=new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_capture_record)
                .setContentTitle("OWOrnate 截屏与录屏").setContentText(text).setOngoing(true).setOnlyAlertOnce(true);
        if(allowStop){
            PendingIntent stop=PendingIntent.getService(this,0,new Intent(this,CaptureService.class).putExtra("action","stop"),PendingIntent.FLAG_UPDATE_CURRENT);
            builder.addAction(new Notification.Action.Builder(R.drawable.ic_capture_stop,"停止",stop).build());
        }
        return builder.build();
    }
    private void show(String text,boolean stop){main.post(()->{if(!destroyed)((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION,notification(text,stop));});}
    private void toast(String text){main.post(()->{if(!destroyed)Toast.makeText(this,text,Toast.LENGTH_LONG).show();});}
    private void finishService(){final int request=currentRequest;main.post(()->{
        if(!destroyed&&latestStartId==request){
            stopForeground(true);
            boolean stopped=stopSelfResult(request);
            if(!stopped)stopped=stopService(new Intent(this,CaptureService.class));
            android.util.Log.i("OWCapture","finish request="+request+" stopped="+stopped);
        }
    });}
    @Override public void onDestroy(){
        destroyed=true;main.removeCallbacksAndMessages(null);
        if(screen!=null&&screen.isHeld())screen.release();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}
