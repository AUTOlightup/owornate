package dev.codex.watchworkshop;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Quick recording options. Native session confirmation never blocks the first frame. */
public final class CaptureOptionsActivity extends WatchActivity {
    private static final String PREFERENCES="capture_options";
    private static final String KEY_BITRATE="bitrate";
    private static final int LOW_BITRATE=600000;
    private static final int DEFAULT_BITRATE=1200000;
    private static final int HIGH_BITRATE=2400000;
    private static final int START_DELAY_MS=200;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final int[] rates={LOW_BITRATE,DEFAULT_BITRATE,HIGH_BITRATE};
    private final TextView[] quality=new TextView[3];
    private int bitrate=DEFAULT_BITRATE,screenWidth,screenHeight;
    private boolean pendingStart,reading,statusConfirmed,details;
    private String readError="";
    private TextView summary,problem,primary;
    private final SharedPreferences.OnSharedPreferenceChangeListener runtimeChanged=(store,key)->{
        if(!destroyed()&&!isFinishing()&&("phase".equals(key)||"error".equals(key)))updateState();
    };

    @Override protected void onCreate(Bundle saved){
        super.onCreate(saved);
        bitrate=loadBitrate();
        DisplayMetrics metrics=new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
        screenWidth=metrics.widthPixels;screenHeight=metrics.heightPixels;
        details=saved!=null&&saved.getBoolean("details");
        if(saved!=null)remember(details?"record-details":"record",saved.getInt("scroll",0));
        body.setPadding(dp(8),dp(4),dp(8),0);
        LinearLayout footer=new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setPadding(dp(8),0,dp(8),dp(6));
        primary=new WorkshopUi(this,footer).button("正在确认状态…",true,this::primaryAction);
        pinBelowContent(footer);
        showPage();
    }

    @Override protected void onStart(){
        super.onStart();
        CaptureManager.preferences(this).registerOnSharedPreferenceChangeListener(runtimeChanged);
        refreshStatus();
    }

    private void refreshStatus(){
        if(reading)return;
        reading=true;statusConfirmed=false;readError="";updateState();
        CaptureService.refreshStatus(this,error->{
            if(destroyed()||isFinishing())return;
            reading=false;statusConfirmed=error==null;
            readError=error==null?"":WatchActivity.message(error);
            updateState();
        });
    }

    @Override protected void onSaveInstanceState(Bundle saved){
        super.onSaveInstanceState(saved);
        saved.putBoolean("details",details);saved.putInt("scroll",scrollY());
    }

    private void primaryAction(){
        if(pendingStart||reading)return;
        if(!statusConfirmed){refreshStatus();return;}
        if(active()){cancel();return;}
        pendingStart=true;saveBitrate();updateState();
        setResult(RESULT_OK);finishAndRemoveTask();
        overridePendingTransition(0,0);
    }

    @Override protected void onStop(){
        CaptureManager.preferences(this).unregisterOnSharedPreferenceChangeListener(runtimeChanged);
        super.onStop();
        if(!pendingStart)return;
        pendingStart=false;
        final Context app=getApplicationContext();
        final int selected=bitrate;
        // One settling delay after this window is gone; the service does not add another.
        handler.postDelayed(()->CaptureService.startRecording(app,selected),START_DELAY_MS);
    }

    private void cancel(){setResult(RESULT_CANCELED);finish();overridePendingTransition(0,0);}
    @Override public void onBackPressed(){
        if(details){details=false;showPage();}else cancel();
    }

    private void showPage(){
        rebuild(details?"record-details":"record",()->{
            ui.header(details?"录屏说明":"录屏",false,this::onBackPressed,null);
            problem=ui.text("");problem.setTextSize(11);problem.setTextColor(WorkshopUi.WARNING);
            if(details){
                ui.value("实际分辨率",screenWidth+" × "+screenHeight+" px");
                ui.value("帧率","可变帧率，随画面更新");
                ui.value("格式与时长","H.264 MP4 · 无声 · 最长3分钟");
                ui.value("保存位置","Movies/Screenrecords");
                ui.text("退出设置后开始录制。再次短按控制中心录屏按钮停止，长按打开本页设置。画质会保留到下一次。");
                ui.button("返回画质选择",false,()->{details=false;showPage();});
            }else{
                LinearLayout row=new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                String[] labels={"低","标准","高"};
                for(int i=0;i<rates.length;i++){
                    final int rate=rates[i];
                    quality[i]=new WorkshopUi(this,row).button(labels[i],false,()->choose(rate));
                    LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f);
                    if(i>0)lp.leftMargin=dp(6);
                    quality[i].setLayoutParams(lp);
                }
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.bottomMargin=dp(6);body.addView(row,lp);
                summary=ui.text("");summary.setTextSize(11);
                ui.button("更多说明",false,()->{details=true;showPage();});
                updateQuality();
            }
        });
        updateState();
    }

    private boolean active(){
        String phase=CaptureManager.preferences(this).getString("phase","idle");
        return "starting".equals(phase)||"recording".equals(phase)||"stopping".equals(phase);
    }

    /** Session updates only touch existing labels; choices and scroll position stay put. */
    private void updateState(){
        boolean active=active();
        String label=pendingStart?"正在返回…":reading||(!statusConfirmed&&readError.isEmpty())?
                "正在确认状态…":!statusConfirmed?"重新读取状态":active?"返回原界面":"开始录屏";
        primary.setText(label);primary.setContentDescription(label);
        boolean enabled=!pendingStart&&!reading&&(statusConfirmed||!readError.isEmpty());
        primary.setEnabled(enabled);primary.setAlpha(enabled?1f:0.45f);
        if(!details)summary.setText(statusConfirmed&&active?"录制/保存中 · 下一段画质":
                (bitrate/1000000f)+" Mbps · 约"+estimatedMegabytes()+" MB/分");
        String error=readError.isEmpty()?CaptureManager.preferences(this).getString("error",""):"状态读取失败："+readError;
        problem.setText(error);problem.setVisibility(error.isEmpty()?View.GONE:View.VISIBLE);
    }

    private void choose(int rate){
        if(bitrate==rate)return;
        bitrate=rate;saveBitrate();updateQuality();updateState();
    }

    private void updateQuality(){
        for(int i=0;i<rates.length;i++){
            boolean selected=bitrate==rates[i];
            GradientDrawable background=new GradientDrawable();
            background.setCornerRadius(dp(WorkshopUi.BTN_RADIUS));
            background.setColor(selected?WorkshopUi.ACCENT:WorkshopUi.SOFT);
            quality[i].setBackground(new RippleDrawable(ColorStateList.valueOf(0x33ffffff),background,null));
            quality[i].setTextColor(selected?WorkshopUi.ON_ACCENT:WorkshopUi.FG);
            quality[i].setSelected(selected);
            quality[i].setContentDescription(quality[i].getText()+"，"+(rates[i]/1000000f)+" Mbps"+(selected?"，已选择":""));
        }
    }

    private int loadBitrate(){
        int stored=getSharedPreferences(PREFERENCES,MODE_PRIVATE).getInt(KEY_BITRATE,DEFAULT_BITRATE);
        return stored==LOW_BITRATE||stored==DEFAULT_BITRATE||stored==HIGH_BITRATE?stored:DEFAULT_BITRATE;
    }
    private void saveBitrate(){getSharedPreferences(PREFERENCES,MODE_PRIVATE).edit().putInt(KEY_BITRATE,bitrate).apply();}
    private String estimatedMegabytes(){return bitrate==LOW_BITRATE?"4.5":bitrate==HIGH_BITRATE?"18":"9";}
}
