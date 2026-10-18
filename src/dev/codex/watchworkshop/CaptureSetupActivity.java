package dev.codex.watchworkshop;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

/** OWOrnate module management. Deployment and enable state share RuntimeStateStore. */
public final class CaptureSetupActivity extends WatchActivity {
    private boolean busy, installing;
    private String operationError="", notice="";
    private StatusObservation<CaptureModuleState> observation;
    private final SharedPreferences.OnSharedPreferenceChangeListener changed=(store,key)->{
        if(!destroyed()&&"capture".equals(key)){sync();render();}
    };
    @Override protected void onCreate(Bundle saved){super.onCreate(saved);sync();render();perform("read");}
    @Override protected void onStart(){super.onStart();RuntimeStateStore.preferences(this).registerOnSharedPreferenceChangeListener(changed);sync();render();}
    @Override protected void onStop(){RuntimeStateStore.preferences(this).unregisterOnSharedPreferenceChangeListener(changed);super.onStop();}
    private void sync(){observation=RuntimeStateStore.capture(this);}
    private void perform(String action){
        if(busy)return;
        busy=true;installing="install".equals(action);operationError="";notice="";render();
        MonetPages.IO.execute(()->{
            String problem="";
            try {
                if("install".equals(action))WidgetHostManager.enableCapture(getApplicationContext());
                else if("enable".equals(action))WidgetHostManager.setCaptureEnabled(getApplicationContext(),true);
                else if("disable".equals(action))WidgetHostManager.setCaptureEnabled(getApplicationContext(),false);
                WidgetHostManager.captureState(getApplicationContext());
            }catch(Exception e){
                problem=message(e);
                if(!"read".equals(action))try{WidgetHostManager.captureState(getApplicationContext());}catch(Exception ignored){}
            }
            final String detail=problem;
            runOnUiThread(()->{
                if(destroyed())return;
                busy=false;installing=false;operationError=detail;sync();
                if(detail.isEmpty()&&"install".equals(action))notice="模块已安装并启用。到控制中心第二页使用两个按钮，长按进入对应设置。";
                render();
            });
        });
    }
    private void render(){rebuild("capture-module",()->{
        CaptureModuleState state=observation.value;
        ui.header("截屏与录屏",false,this::onBackPressed,null);
        ui.note("OWOrnate 功能模块 · 控制中心快捷入口");
        if(!notice.isEmpty())ui.note(notice,"success");
        if(!operationError.isEmpty())ui.note(operationError,"warning");
        if(!observation.error.isEmpty())ui.note((state==null?"读取失败：":"上次："+state.message()+"；本次读取失败：")+observation.error,"warning");
        else if(state!=null)ui.note(state.message(),state.enabled()?"success":"");
        if(busy)ui.note("正在处理，请稍候…");
        if(state!=null&&(state.enabled()||"disabled".equals(state.kind))){
            ui.toggle("启用模块", "控制中心显示截屏与录屏按钮", state.enabled(), ()->{
                if(!busy&&observation.error.isEmpty())perform(state.enabled()?"disable":"enable");
            }).setEnabled(!busy&&observation.error.isEmpty());
            ui.note("停用会先停止当前录屏，再隐藏两个按钮；已保存文件和画质设置保留。");
        }
        if(state!=null&&("available".equals(state.kind)||"installed".equals(state.kind))){
            ui.button(state.installed()?"重新接入模块":"安装模块",true,!busy,()->perform("install"));
            ui.note("安装或重新接入会重新加载一次表盘，屏幕可能短暂黑屏。需要已有共享运行组件。","warning");
        }
        ui.title("功能设置");
        ui.row("截屏", "PNG · 保存位置与操作", "layers", ()->startActivity(new Intent(this,CaptureScreenshotActivity.class)));
        ui.row("录屏", "码率与画质 · 最长 3 分钟", "layers", ()->startActivity(new Intent(this,CaptureOptionsActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK|Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)));
        ui.value("快捷操作", "短按执行；长按打开 OWOrnate 对应页面");
        ui.value("录屏格式", "原屏 H.264 MP4 · 无声 · 帧率跟随屏幕");
        ui.value("模块版本", "原生捕获 v1 · 共享 Hook v12");
        ui.button("重新读取状态",false,!busy,()->perform("read"));
    });}
    @Override public void onBackPressed(){if(!installing)super.onBackPressed();}
}
