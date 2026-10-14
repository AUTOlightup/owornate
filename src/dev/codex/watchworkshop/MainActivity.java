package dev.codex.watchworkshop;

import android.app.Activity;
import dev.codex.watchworkshop.palette.ThemePalettePreview;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.*;
import android.util.AtomicFile;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

/** Native release UI. Only verified theme operations are exposed. */
public final class MainActivity extends Activity {
    private static final int BG=0xff000000,SURFACE=0xff12161e,SOFT=0xff191f29,FG=0xfff0f3fa,MUTED=0xffa4aec0,GOLD=0xffebc16c,WARN=0xffffc857;
    private static final ExecutorService io=Executors.newSingleThreadExecutor();
    private final ArrayDeque<String> stack=new ArrayDeque<>();
    private final HashMap<String,Integer> positions=new HashMap<>();
    private android.content.SharedPreferences prefs;
    private LinearLayout body;private WorkshopUi ui;private ScrollView scroll;private String page="home",renderedPage="";
    private boolean busy=false,saveBusy=false,destroyed=false;private String probeError="",exportPath="";
    private boolean themeBusy=false,themeConnected=false,canRollback=false;private Integer autoSeed=null;private JSONObject themeState,rollbackConfig;private String themeMessage="";
    private JSONObject snapshot;private ThemeConfig draft;private long revision,updatedAt;private String resultAction="",resultError="";private boolean resultOk;
    private ModuleManager.State moduleState;
    private LatestThemeQueue<ThemeConfig> themeWrites;
    private boolean moduleRefreshing=false,opBusy=false,firstResume=true;
    private String moduleError="",operation="",operationReturn="theme";
    @Override public void onCreate(Bundle saved){super.onCreate(saved);getWindow().setFlags(1024,1024);getWindow().setNavigationBarColor(BG);prefs=getSharedPreferences("workshop",MODE_PRIVATE);
        try{snapshot=new JSONObject(prefs.getString("probe","{}"));if(snapshot.length()==0)snapshot=null;}catch(Exception ignored){}
        try{draft=new ThemeConfig(prefs.getString("mode","auto"),prefs.getInt("seed",GOLD),prefs.getBoolean("corners",true));}catch(Exception bad){draft=new ThemeConfig("auto",GOLD,true);}
        revision=prefs.getLong("revision",0);updatedAt=prefs.getLong("updatedAt",0);
        if(saved!=null){page=saved.getString("page","home");ArrayList<String>a=saved.getStringArrayList("stack");if(a!=null)stack.addAll(a);positions.put(page,saved.getInt("scroll",0));}
        else if(!prefs.getBoolean("welcomed",false))page="welcome";
        if(page.equals("progress")){page=prefs.getBoolean("pendingTheme",false)?"theme":"mine";stack.clear();stack.push("home");if(page.equals("theme"))stack.push("mine");}
        if(Arrays.asList("theme-confirm","theme-restore","versions","disable").contains(page))page="theme";
        if(page.equals("records"))page="home";
        
        operationReturn=prefs.getString("operationReturn","theme");
        resultAction=prefs.getString("resultAction","");resultOk=prefs.getBoolean("resultOk",false);resultError=prefs.getString("resultError","");
        if(page.equals("result")&&resultAction.isEmpty())page="mine";
        if(page.equals("category"))page="discover";
        if(page.equals("device"))page="check";
        if(page.equals("preferences"))page="settings";
        stack.removeAll(Arrays.asList("category","device","preferences","welcome","records","versions","disable","theme-confirm"));
        if(stack.contains(page)){while(!stack.pop().equals(page)){}}
        initThemeWrites();
        render();io.execute(()->{try{new RsaKeyStore(new File(getFilesDir(),"adb")).publicKeyLine();}catch(Exception e){runOnUiThread(()->{probeError="本机密钥准备失败";if(!destroyed)render();});}});
        RuntimeSupport.ensureAsync(this,false);

    }
    @Override protected void onResume(){
        super.onResume();
        if(firstResume){firstResume=false;refreshModule();}
        else if(page.equals("home")&&!working())refreshModule();
    }
    @Override protected void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);b.putString("page",page);b.putStringArrayList("stack",new ArrayList<>(stack));b.putInt("scroll",scroll.getScrollY());}
    private float downX,downY;private boolean swipeTracking;
    /** 右滑 = 返回上一界面；根页面右滑才回到表盘。系统自带的右滑关闭已在主题中关闭。 */
    @Override public boolean dispatchTouchEvent(MotionEvent e){
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:downX=e.getRawX();downY=e.getRawY();swipeTracking=true;break;
            case MotionEvent.ACTION_POINTER_DOWN:case MotionEvent.ACTION_CANCEL:swipeTracking=false;break;
            case MotionEvent.ACTION_UP:
                if(swipeTracking){swipeTracking=false;float dx=e.getRawX()-downX,dy=Math.abs(e.getRawY()-downY);
                    if(dx>dp(64)&&dy<dx*.5f){e.setAction(MotionEvent.ACTION_CANCEL);super.dispatchTouchEvent(e);onBackPressed();return true;}}
                break;
        }
        return super.dispatchTouchEvent(e);
    }
    @Override public void onBackPressed(){if(page.equals("progress"))return;if(page.equals("result")){if(!working())finishResult();return;}if(stack.isEmpty()){if(page.equals("home")||page.equals("welcome"))finish();else returnHome();return;}positions.put(page,scroll.getScrollY());page=stack.pop();render();if(page.equals("home")&&!working())refreshModule();}
    @Override protected void onDestroy(){destroyed=true;super.onDestroy();}
    private int dp(float n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    private GradientDrawable shape(int c,int radius){GradientDrawable d=new GradientDrawable();d.setColor(c);d.setCornerRadius(dp(radius));return d;}
    private android.graphics.drawable.Drawable press(int c,int radius){return new RippleDrawable(android.content.res.ColorStateList.valueOf(0x33ffffff),shape(c,radius),shape(0xffffffff,radius));}
    private TextView txt(String s,int size,int color){TextView v=new TextView(this);v.setText(s);v.setTextColor(color);v.setTextSize(size);v.setFontFeatureSettings("kern");v.setLineSpacing(dp(1),1.08f);v.setIncludeFontPadding(true);return v;}
    private void add(View v){LinearLayout.LayoutParams l=new LinearLayout.LayoutParams(-1,-2);l.bottomMargin=dp(6);body.addView(v,l);}
    private void text(String s){ui.text(s);}
    private void note(String s){ui.note(s);}
    private void title(String s){ui.title(s);}
    private void value(String label,String val){ui.value(label,val);}
    private void button(String label,boolean primary,Runnable fn){ui.button(label,primary,fn);}
    private void row(String label,String sub,Runnable fn){ui.row(label,sub,"info",fn);}
    private void nav(String label,String sub,String icon,String target){ui.row(label,sub,icon,()->go(target));}
    private void choose(String label,String sub,boolean selected,Runnable fn){View v=ui.choice(label,sub,selected,fn);v.setEnabled(editable());v.setAlpha(editable()?1f:.4f);}
    private void toggle(String label,boolean checked,Runnable fn){View v=ui.toggle(label,"调整控制中心按钮轮廓",checked,fn);v.setEnabled(editable());v.setAlpha(editable()?1f:.4f);}
    private void go(String target){
        // Read-only refreshes must not block browsing. Writes keep their existing guard.
        if(page.equals("progress"))return;
        positions.put(page,scroll.getScrollY());
        if(stack.contains(target)){while(!stack.pop().equals(target)){}}
        else if(!page.equals(target))stack.push(page.equals("welcome")?"home":page);
        page=target;render();
        if(target.equals("theme")){if(themeReachable())themeAction("preview");else if(!working())refreshModule();}
    }

    private void render(){if(destroyed)return;if(scroll!=null&&page.equals(renderedPage))positions.put(page,scroll.getScrollY());renderedPage=page;scroll=new ScrollView(this);scroll.setFillViewport(false);scroll.setBackgroundColor(BG);body=new LinearLayout(this);body.setOrientation(1);body.setPadding(dp(8),dp(10),dp(8),dp(32));scroll.addView(body);setContentView(scroll);
        boolean root=page.equals("home")||page.equals("welcome")||page.equals("progress");ui=new WorkshopUi(this,body);if(page.equals("theme"))ui.headerToggle(pageTitle(),this::onBackPressed,moduleState!=null&&moduleState.installed()&&moduleState.activeKnown?Boolean.valueOf(moduleState.active):null,!working()&&moduleState!=null&&moduleState.installed()&&moduleState.activeKnown,()->moduleAction(moduleState.active?"disable":"enable"));
        else ui.header(pageTitle(),root,root?null:this::onBackPressed,page.equals("home")?()->go("settings"):null);
        switch(page){case "welcome":welcome();break;case "home":home();break;case "mine":mine();break;case "discover":discover();break;case "detail":detail();break;case "theme":theme();break;case "theme-restore":themeRestore();break;case "result":result();break;case "install":installPlan();break;case "uninstall":moduleConfirm();break;case "progress":progress();break;case "licenses":licenses();break;case "technical":technical();break;case "modes":modes();break;case "colors":colors();break;case "check":checkPage();break;case "settings":settings();break;case "guide":guide();break;case "export":exportPage();break;case "about":about();break;default:home();}
        int pos=positions.containsKey(page)?positions.get(page):0;scroll.post(()->scroll.scrollTo(0,pos));
    }
    private String pageTitle(){switch(page){case "home":case "welcome":return "OWOrnate";case "mine":return "我的优化";case "discover":return "发现优化";case "detail":return "莫奈取色";case "theme":return "莫奈取色";case "install":return "安装方案";case "uninstall":return "卸载优化";case "progress":return "正在应用";case "theme-restore":return "恢复设置";case "result":return "操作结果";case "licenses":return "开源许可";case "technical":return "技术详情";case "modes":return "配色方式";case "colors":return "固定颜色";case "check":return "运行环境";case "settings":return "设置";case "guide":return "首次设置";case "export":return "导出配置";case "about":return "关于 OWOrnate";default:return "OWOrnate";}}
    private void welcome(){
        ui.hero("spark","让优化，留在腕上","分类安装 · 随时管理","");
        button("开始检查",true,()->{prefs.edit().putBoolean("welcomed",true).apply();go("check");probe();});
        button("先逛逛",false,()->{prefs.edit().putBoolean("welcomed",true).apply();returnHome();});
    }

    private void home(){
        boolean installed=moduleState!=null&&moduleState.installed();
        ui.summary(moduleState==null?null:(installed?1:0),moduleState==null?"项优化":"项优化，已纳入管理",moduleRefreshing?"正在确认运行状态":moduleState==null?"运行状态待检查":installed?!moduleState.activeKnown?"运行状态待确认":moduleState.active?"运行状态已确认":"莫奈取色已停用":"从发现优化开始");
        nav("我的优化","","layers","mine");
        nav("发现优化","","compass","discover");
        
        
    }

    private void mine(){
        if(moduleState!=null&&moduleState.installed()){
            title(!moduleState.activeKnown?"状态待确认":moduleState.active?"正在使用":"已停用");
            nav("莫奈取色",statusText(),"palette","theme");
        }else if(moduleState==null){
            note(moduleError.isEmpty()?"正在确认已安装的优化…":moduleError);
            ui.button("重新读取状态",true,!working(),this::refreshModule);
        }else{
            ui.hero("layers","还没有优化","从一个小改动开始","");
            nav("发现优化","浏览可安装的功能","compass","discover");
        }
    }

    private void discover(){
        List<ModuleCatalog.Entry> catalog=ModuleCatalog.sorted(Arrays.asList(
            new ModuleCatalog.Entry("monet","莫奈取色",moduleState!=null&&moduleState.installed())));
        for(ModuleCatalog.Entry item:catalog)nav(item.title,item.installed?"已安装 · "+statusText():moduleState==null?"内置 · 状态待确认":"内置 · 离线安装","palette","detail");
    }
    private void detail(){
        ui.hero("palette","莫奈取色","让控制中心与表盘配色协调","");
        if(moduleState!=null&&moduleState.installed()){
            ui.note("已安装 · "+statusText(),moduleState.activeKnown&&moduleState.active?"success":"");
            button("打开配置",true,()->go("theme"));
        }else if(moduleState!=null&&moduleState.kind.equals("available")){
            ui.note("适配当前手表 · 可安装","success");
            button("安装",true,()->go("install"));
        }else if(moduleState!=null&&moduleState.kind.equals("setup")){
            note("完成一次首次部署，即可在手表离线安装。");nav("首次设置","","book","guide");
        }else if(moduleState!=null){
            ui.note("已有组件不兼容，请检查运行环境。","warning");nav("运行环境","","shield","check");
        }else{
            note(moduleError.isEmpty()?"正在读取安装状态…":moduleError);
            ui.button("重新读取状态",true,!working(),this::refreshModule);
        }
        title("功能介绍");
        text("自动取色：跟随当前表盘的真实配色。\n固定颜色：选择喜欢的控制中心颜色。\n系统原色：保留系统配色。\n连续圆角：独立调整按钮轮廓。");
        value("适配设备","OPPO Watch 2 46mm · A.92");
        value("模块版本","莫奈组件 v6");
        value("安装来源","应用内置 · 无需联网");
        note("仅影响 Android 控制中心。");
    }

    private void theme(){
        if(moduleState!=null&&!moduleState.installed()){
            note("尚未安装莫奈取色。");nav("查看功能介绍","","compass","detail");return;
        }
        add(new ThemePreview(this));
        nav("配色方式",draft.modeLabel(),"palette","modes");
        if(draft.mode.equals("fixed"))nav("固定颜色",colorName(draft.seed),"dot","colors");
        toggle("连续圆角",draft.corners,()->save(new ThemeConfig(draft.mode,draft.seed,!draft.corners)));
        if(opBusy)note(operation.equals("disable")?"正在停用…":"正在启用…");
        else if(moduleRefreshing||themeBusy)note(themeWrites.isRunning()?"正在应用…":"正在读取当前设置…");
        else if(moduleState!=null&&moduleState.activeKnown&&!moduleState.active)note("已停用，启用后可调整设置。");
        else if(themeConnected)note("选择后自动应用");
        else note("当前设置尚未确认。");
        if(!moduleError.isEmpty())ui.note(moduleError,"warning");
        if(!themeMessage.isEmpty())ui.note(themeMessage,themeConnected?"":"warning");
        if(!themeConnected||!themeMessage.isEmpty())ui.button("重新读取状态",false,!working(),this::refreshModule);
        if(moduleState!=null&&moduleState.installed()&&!moduleState.activeKnown)
            ui.button("重试启用",false,!working(),()->moduleAction("enable"));
        ui.button("刷新表盘配色",false,!working()&&themeReachable(),()->themeAction("preview"));
        ui.button("恢复上一次设置",false,canRollback&&themeConnected&&themeReachable()&&!working(),()->go("theme-restore"));
        value("当前版本","莫奈组件 v6");
        if(moduleState!=null&&moduleState.removable())ui.button("卸载",false,!working(),()->go("uninstall"));
        else if(moduleState!=null&&moduleState.installed())note("共享组件，无法单独卸载。可用右上开关停用。");
    }

    private String modeName(String mode){return "auto".equals(mode)?"自动取色":"fixed".equals(mode)?"固定颜色":"original".equals(mode)?"系统原色":"未知";}
    private void themeRestore(){
        text("恢复上一次设置？");
        if(rollbackConfig!=null){
            value("配色方式",modeName(rollbackConfig.optString("mode")));
            if("fixed".equals(rollbackConfig.optString("mode")))value("颜色",rollbackConfig.optString("seedArgb"));
            value("圆角",rollbackConfig.optBoolean("corners")?"连续圆角":"原生圆角");
        }
        note("恢复最近一次成功应用之前的设置。");
        ui.button("确认恢复",true,themeConnected&&themeReachable()&&!working()&&canRollback&&rollbackConfig!=null,()->{onBackPressed();themeAction("rollback");});
        button("取消",false,this::onBackPressed);
    }


    private void themeAction(String action){
        if(destroyed||working()||!themeReachable())return;
        final boolean mutation="rollback".equals(action);
        themeBusy=true;themeMessage="";render();
        io.execute(()->{
            try{
                JSONObject ack;String feedback="";
                if(mutation){
                    markPending();ack=ThemeRuntime.exchange(this,"rollback",null);ModuleManager.rememberThemeEnabled(this);
                }else{
                    JSONObject current=ThemeRuntime.exchange(this,"read",null);
                    try{ack=ThemeRuntime.exchange(this,"preview",configFromState(current.getJSONObject("state")));}
                    catch(Exception previewError){ack=current;feedback="预览读取未完成，已显示当前实际设置。";}
                }
                persistConfirmed(ack,mutation);
                final JSONObject confirmed=ack;final String message=feedback;
                runOnUiThread(()->{if(destroyed)return;themeBusy=false;acceptTheme(confirmed,true);themeMessage=mutation?"已恢复上一次设置":message;render();});
            }catch(Exception e){
                if(mutation)recoverTheme(e);
                else runOnUiThread(()->{if(destroyed)return;themeBusy=false;themeConnected=false;themeMessage=messageOf(e);render();});
            }
        });
    }
    private ThemeConfig configFromState(JSONObject state)throws Exception {
        return new ThemeConfig(state.getString("mode"),ThemeRuntime.color(state.getString("seed")),state.getBoolean("corners"));
    }
    private void markPending()throws IOException {
        if(!prefs.edit().putBoolean("pendingTheme",true).commit())throw new IOException("无法保存待确认状态，本次未提交");
    }
    private void persistConfirmed(JSONObject ack,boolean changed)throws Exception {
        JSONObject current=ack.getJSONObject("state");ThemeConfig actual=configFromState(current);
        SharedPreferences.Editor edit=prefs.edit().putString("lastThemeState",current.toString()).putBoolean("pendingTheme",false)
            .putString("mode",actual.mode).putInt("seed",actual.seed).putBoolean("corners",actual.corners);
        if(changed)edit.putLong("revision",prefs.getLong("revision",0)+1).putLong("updatedAt",System.currentTimeMillis());
        if(!edit.commit())throw new IOException("已读取实际设置，但本地记录保存失败");
    }
    private void acceptTheme(JSONObject ack,boolean syncSelection){
        try{
            themeState=ack.getJSONObject("state");themeConnected=true;
            ThemeConfig actual=configFromState(themeState);
            if(syncSelection)draft=actual;
            JSONObject palette=ack.optJSONObject("preview");
            autoSeed=actual.mode.equals("auto")?(palette==null?actual.seed:ThemeRuntime.color(palette.getString("seed"))):null;
            canRollback=ack.optBoolean("canRollback");rollbackConfig=ack.optJSONObject("rollbackConfig");
            revision=prefs.getLong("revision",0);updatedAt=prefs.getLong("updatedAt",0);
        }catch(Exception e){themeConnected=false;themeMessage=messageOf(e);}
    }
    private void initThemeWrites(){
        themeWrites=new LatestThemeQueue<>(io,new LatestThemeQueue.Worker<ThemeConfig>(){
            public void apply(ThemeConfig target)throws Exception {
                markPending();JSONObject ack=ThemeRuntime.exchange(MainActivity.this,"apply",target);
                ModuleManager.rememberThemeEnabled(MainActivity.this);persistConfirmed(ack,true);
                runOnUiThread(()->{if(destroyed)return;acceptTheme(ack,draft==target);themeMessage="";render();});
            }
            public void failed(Exception error){recoverTheme(error);}
            public void idle(){runOnUiThread(()->{if(destroyed)return;themeBusy=themeWrites.isRunning();render();});}
        });
    }
    private void recoverTheme(Exception error){
        runOnUiThread(()->{if(destroyed)return;themeConnected=false;themeMessage="操作未确认，正在重新读取…";render();});
        JSONObject recovered=null;String message="操作未确认："+messageOf(error);
        try{recovered=ThemeRuntime.exchange(this,"read",null);persistConfirmed(recovered,false);message+="；已按实际状态更新。";}
        catch(Exception readError){message+="；请重新读取状态。";}
        final JSONObject actual=recovered;final String detail=message;
        runOnUiThread(()->{
            if(destroyed)return;
            themeBusy=themeWrites.isRunning();themeConnected=false;
            if(actual!=null)acceptTheme(actual,true);
            themeMessage=detail;render();
        });
    }
    private boolean editable(){
        return !destroyed&&themeConnected&&themeReachable()&&!opBusy&&!saveBusy&&!moduleRefreshing&&(!themeBusy||themeWrites.isRunning());
    }

    private void modes(){choose("自动取色","跟随表盘配色",draft.mode.equals("auto"),()->select(new ThemeConfig("auto",draft.seed,draft.corners)));choose("固定颜色","使用选择的主题色",draft.mode.equals("fixed"),()->select(new ThemeConfig("fixed",draft.seed,draft.corners)));choose("系统原色","恢复默认配色",draft.mode.equals("original"),()->select(new ThemeConfig("original",draft.seed,draft.corners)));}
    private static final int[] PRESETS={GOLD,0xff91bdff,0xffbca8f5,0xff99d5b4,0xffefa898,0xffe3e9f1};
    private static final String[] COLOR_NAMES={"暖金","雾蓝","淡紫","薄荷","珊瑚","月白"};
    private String colorName(int c){for(int i=0;i<PRESETS.length;i++)if(c==PRESETS[i])return COLOR_NAMES[i];return String.format(Locale.ROOT,"#%06X",c&0xffffff);}
    private void colors(){for(int y=0;y<2;y++){LinearLayout line=new LinearLayout(this);for(int x=0;x<3;x++){final int i=y*3+x;LinearLayout cell=new LinearLayout(this);cell.setOrientation(1);cell.setGravity(Gravity.CENTER);cell.setMinimumHeight(dp(56));cell.setPadding(dp(4),dp(7),dp(4),dp(7));GradientDrawable bg=shape(SURFACE,12);if(draft.seed==PRESETS[i])bg.setStroke(dp(1),GOLD);cell.setBackground(new RippleDrawable(android.content.res.ColorStateList.valueOf(0x33ffffff),bg,null));cell.addView(new ColorDot(PRESETS[i],draft.seed==PRESETS[i]),new LinearLayout.LayoutParams(dp(20),dp(20)));TextView label=txt(COLOR_NAMES[i],11,FG);label.setGravity(Gravity.CENTER);cell.addView(label);cell.setContentDescription(COLOR_NAMES[i]+(draft.seed==PRESETS[i]?"，已选择":""));cell.setEnabled(editable());cell.setAlpha(editable()?1f:.4f);cell.setOnClickListener(v->select(new ThemeConfig(draft.mode,PRESETS[i],draft.corners)));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);if(x<2)lp.rightMargin=dp(6);line.addView(cell,lp);}add(line);}note("选择后自动应用。仅固定配色时生效。");button("返回主题设置",false,this::onBackPressed);}
    private void select(ThemeConfig next){save(next,true);}
    private void save(ThemeConfig next){save(next,false);}
    private void save(ThemeConfig next,boolean back){
        if(!editable()){Toast.makeText(this,"请先启用并读取当前设置",0).show();return;}
        boolean same=draft.mode.equals(next.mode)&&draft.seed==next.seed&&draft.corners==next.corners;
        if(!same){
            if(!themeWrites.submit(next)){Toast.makeText(this,"正在确认上次操作，请稍候",0).show();return;}
            draft=next;themeBusy=true;themeMessage="";
        }
        if(back&&!stack.isEmpty())page=stack.pop();
        render();
    }
    private JSONObject exportJson(JSONObject state)throws Exception {
        ThemeConfig actual=configFromState(state);JSONObject j=new JSONObject();
        j.put("schemaVersion",1);j.put("moduleId","control-center-theme");j.put("mode",actual.mode);j.put("seedArgb",actual.seedHex());j.put("corners",actual.corners);
        j.put("applied",false);j.put("deviceStateConfirmed",true);j.put("capturedAt",System.currentTimeMillis());j.put("origin","dev.codex.watchworkshop/1.0.0");return j;
    }
    private void exportDraft(){
        if(working()||!themeReachable())return;
        saveBusy=true;render();
        io.execute(()->{
            try{
                String json=exportJson(ThemeRuntime.exchange(this,"read",null).getJSONObject("state")).toString(2);
                File base=getExternalFilesDir(null);if(base==null)base=getFilesDir();File dir=new File(base,"exports");
                if(!dir.exists()&&!dir.mkdirs())throw new IOException("无法创建导出目录");
                File file=new File(dir,"theme-config.json");AtomicFile af=new AtomicFile(file);FileOutputStream out=null;
                try{out=af.startWrite();out.write(json.getBytes("UTF-8"));af.finishWrite(out);}catch(Exception e){if(out!=null)af.failWrite(out);throw e;}
                if(!prefs.edit().putString("lastExport",file.getAbsolutePath()).commit())throw new IOException("导出位置保存失败");
                runOnUiThread(()->{saveBusy=false;if(!destroyed){exportPath=file.getAbsolutePath();if(page.equals("technical"))go("export");else render();}});
            }catch(Exception e){runOnUiThread(()->{saveBusy=false;if(!destroyed){Toast.makeText(this,"导出失败："+messageOf(e),1).show();render();}});}
        });
    }
    private void exportPage(){
        text("配置已导出");note("这是当前已确认设置的快照，导出文件不执行更改。");
        String p=exportPath.isEmpty()?prefs.getString("lastExport",""):exportPath;value("保存位置",p);
        button("复制文件路径",false,()->{((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("配置路径",p));Toast.makeText(this,"路径已复制",0).show();});
    }

    private void result(){
        String heading=resultAction.equals("rollback")?"已经恢复":resultAction.equals("install")?"安装完成":resultAction.equals("disable")?"已停用":resultAction.equals("uninstall")?"已卸载":"已生效";
        ui.hero(resultOk?"check":"warning",resultOk?heading:"结果待确认",resultOk?resultAction.equals("uninstall")?"管理器保留，可在手表上重新安装":"更改已经应用到手表":"请重新读取实际状态后再操作。",resultOk?"success":"warning");
        if(!resultOk)note(resultError);
        String destination=resultDestination();
        ui.button(resultOk?destination.equals("detail")?"返回功能介绍":destination.equals("theme")?"继续配置":"返回我的优化":"重新读取状态",true,!working(),this::finishResult);
    }



    private void technical(){value("App",getPackageName()+" / 1.0.0 (7)");value("本机通道",AdbClient.HOST+":"+AdbClient.PORT+" · root adbd");if(snapshot!=null){value("组件指纹",snapshot.optString("themeSha","未读取"));value("检查时间",time(snapshot.optLong("checkedAt")));}ui.button("导出当前配置",false,!working()&&themeReachable(),this::exportDraft);value("启动记录",RuntimeSupport.lastResult(this));android.content.SharedPreferences r=getSharedPreferences("runtime",0);value("系统启动记录",r.getString("bootId","").isEmpty()?"尚无本包独立启动记录":r.getBoolean("bootOk",false)?"上次启动已确认 · 当前启动请用电脑验证":"上次启动未确认");}
    private void licenses(){for(String file:new String[]{"material-color-utilities.txt","frida-core.txt","frida-core-library.txt","frida-gum.txt"}){title(file);try{InputStream in=getAssets().open("licenses/"+file);ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);in.close();text(out.toString("UTF-8"));}catch(IOException e){note("完整许可随源码与安装包提供。");}}}
    private void device(){value("型号",Build.MODEL);value("系统",Build.DISPLAY);value("Android",Build.VERSION.RELEASE+" / API "+Build.VERSION.SDK_INT);value("ABI",android.text.TextUtils.join(", ",Build.SUPPORTED_ABIS));android.util.DisplayMetrics m=getResources().getDisplayMetrics();value("当前画布",m.widthPixels+" × "+m.heightPixels+"px / density "+m.density);StatFs stat=new StatFs(getFilesDir().getAbsolutePath());value("可用空间",(stat.getAvailableBytes()/1024/1024)+" MB");value("适配范围","OPPO Watch 2 46mm / A.92");}
    private void probe(){if(busy)return;busy=true;probeError="";render();io.execute(()->{try{JSONObject r=RuntimeProbe.read(this);if(!prefs.edit().putString("probe",r.toString()).commit())throw new IOException("检测结果保存失败");runOnUiThread(()->{snapshot=r;busy=false;if(!destroyed)render();});}catch(Exception e){runOnUiThread(()->{busy=false;probeError=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();if(!destroyed)render();});}});}
    private void checkPage(){
        if(busy||moduleRefreshing)ui.hero("scan","正在检查","读取设备、授权和模块状态。","");
        else if(!probeError.isEmpty()||!moduleError.isEmpty()){
            ui.hero("key","暂时无法管理","本机连接或授权需要检查。","warning");
            note(probeError.isEmpty()?moduleError:probeError);nav("首次设置","查看部署步骤","book","guide");
        }else if(moduleState!=null){
            boolean ready=moduleState.kind.equals("available")||moduleState.installed();
            ui.hero(ready?"shield":"key",ready?"管理器已就绪":"需要检查环境",statusText(),ready?"success":"warning");
            if(snapshot!=null)value("检查时间",time(snapshot.optLong("checkedAt")));
            if(ready)button("进入 OWOrnate",true,this::returnHome);
            else nav("首次设置","查看部署步骤","book","guide");
        }else note("尚未执行检查。");
        if(!busy&&!moduleRefreshing){device();button("重新检查",false,()->{probe();refreshModule();});}
    }

    private void settings(){nav("运行环境","手表信息与授权状态","shield","check");nav("关于OWOrnate","版本、许可与首次设置","info","about");}
    private void guide(){
        ui.hero("book","只需部署一次","之后在手表上安装和管理优化。","");
        text("1. 在已 root 的手表上，使用电脑完成管理器首次部署。\n2. 回到 OWOrnate，在“发现优化”中打开莫奈取色。\n3. 查看安装方案，点“安装并启用”。");
        note("首次部署入口：Windows 包内的 00-Setup.cmd。完成后，莫奈安装无需电脑或联网。");
        text("已安装的功能在“我的优化”中配置；停用、启用和卸载在功能详情页操作。");
        button("重新检查",true,()->{go("check");probe();refreshModule();});
    }



    private void about(){ui.hero("layers","OWOrnate","1.0.0","");value("包名",getPackageName());text("管理控制中心莫奈取色、固定颜色、系统原色和连续圆角。");note("首次部署需电脑和现有 root adbd。仅影响 Android 控制中心。");nav("开源许可","Material Color Utilities 与 Frida","info","licenses");nav("首次设置","安装与恢复说明","book","guide");nav("技术详情","授权与组件信息","info","technical");text("当前内置莫奈可离线安装。在线目录和其他优化管理尚未接入。");}

    private boolean working(){return themeBusy||saveBusy||opBusy||moduleRefreshing||(themeWrites!=null&&themeWrites.isRunning());}
    private boolean themeReachable(){return moduleState!=null&&moduleState.installed()&&moduleState.activeKnown&&moduleState.active;}
    private String statusText(){
        if(moduleRefreshing)return "正在读取状态…";
        if(moduleState==null)return moduleError.isEmpty()?"运行状态待检查":"状态读取失败";
        return moduleState.message;
    }
    private String messageOf(Exception e){return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
    private String resultDestination(){
        return resultAction.equals("uninstall")&&moduleState!=null&&!moduleState.installed()?"mine":resultAction.equals("install")?"detail":"theme";
    }
    private void finishResult(){
        if(working())return;
        String target=resultDestination();
        if(stack.contains(target)){while(!stack.pop().equals(target)){}}
        else{stack.clear();stack.push("home");if(target.equals("detail"))stack.push("discover");else if(target.equals("theme"))stack.push("mine");}
        page=target;positions.put(page,0);render();refreshModule();
    }
    private void returnHome(){stack.clear();page="home";positions.put(page,0);render();if(!working())refreshModule();}
    private void refreshModule(){
        if(destroyed||working())return;
        moduleRefreshing=true;moduleError="";render();
        io.execute(()->{
            ModuleManager.State state=null;String error="";
            try{state=ModuleManager.read(this);}catch(Exception e){error=messageOf(e);}
            final ModuleManager.State current=state;final String message=error;
            runOnUiThread(()->{
                if(destroyed)return;
                moduleRefreshing=false;moduleState=current;moduleError=message;
                if(!themeReachable())themeConnected=false;
                render();
                if(page.equals("theme")){
                    if(current!=null&&!current.installed()){page="mine";stack.remove("theme");render();}
                    else if(themeReachable())themeAction("preview");
                }
            });
        });
    }
    private void installPlan(){
        value("本次安装","莫奈取色 · 组件 v6");
        value("同时安装","莫奈运行组件");
        value("安装来源","管理器内置 · 无需下载");
        note("基础环境已就绪。安装后启用控制中心主题。");
        ui.button("安装并启用",true,!working()&&moduleState!=null&&moduleState.kind.equals("available"),()->moduleAction("install"));
        button("暂不安装",false,this::onBackPressed);
    }
    private void moduleConfirm(){
        text("卸载莫奈取色？控制中心将恢复系统外观。");
        note("移除莫奈运行组件，保留应用与基础环境，可再次安装。");
        if(moduleState!=null&&moduleState.removable())ui.note("操作会重新加载表盘，可能短暂黑屏。","warning");
        ui.button("确认卸载",true,!working()&&moduleState!=null&&moduleState.removable(),()->moduleAction("uninstall"));
        button("取消",false,this::onBackPressed);
    }
    private void progress(){
        ui.hero("refresh","正在应用更改",operation.equals("install")?"安装内置组件并确认运行状态":"正在等待手表确认","");
        note("完成后显示实际结果，请稍候。");
    }
    private void showResult(String action,boolean ok,String error){
        resultAction=action;resultOk=ok;resultError=error;
        prefs.edit().putString("resultAction",action).putBoolean("resultOk",ok).putString("resultError",error).apply();
        if(page.equals("progress")){page="result";positions.put(page,0);}
        render();
    }
    private void moduleAction(String action){
        if(destroyed||working())return;
        final boolean inline=action.equals("enable")||action.equals("disable");
        opBusy=true;operation=action;operationReturn=action.equals("install")?"detail":action.equals("uninstall")?"mine":"theme";
        prefs.edit().putString("operationReturn",operationReturn).apply();moduleError="";themeMessage="";themeConnected=false;
        if(!inline){page="progress";positions.put(page,0);}render();
        io.execute(()->{
            String error="";ModuleManager.State after=null;
            try{
                if(action.equals("install"))ModuleManager.install(this);
                else if(action.equals("enable"))ModuleManager.setEnabled(this,true);
                else if(action.equals("disable"))ModuleManager.setEnabled(this,false);
                else if(action.equals("uninstall"))ModuleManager.uninstall(this);
            }catch(Exception e){error=messageOf(e);}
            try{after=ModuleManager.read(this);}catch(Exception e){error+=(error.isEmpty()?"":"；")+"状态读取失败："+messageOf(e);}
            final String message=error;final ModuleManager.State current=after;
            runOnUiThread(()->{
                if(destroyed)return;
                opBusy=false;moduleState=current;moduleError=message;
                if(inline){render();if(page.equals("theme")&&themeReachable())themeAction("preview");}
                else showResult(action,message.isEmpty(),message);
            });
        });
    }

    private String time(long t){return t<=0?"尚无记录":new SimpleDateFormat("MM-dd HH:mm",Locale.CHINA).format(new Date(t));}
    private final class ColorDot extends View {final int c;final boolean chosen;Paint p=new Paint(3);ColorDot(int c,boolean chosen){super(MainActivity.this);this.c=c;this.chosen=chosen;}protected void onDraw(Canvas canvas){p.setStyle(Paint.Style.FILL);p.setColor(c);canvas.drawCircle(getWidth()/2f,getHeight()/2f,getWidth()/2f,p);if(chosen){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(1.5f));p.setColor(0xff1e1a11);Path path=new Path();path.moveTo(dp(5),dp(10));path.lineTo(dp(8.5f),dp(13));path.lineTo(dp(15),dp(6.5f));canvas.drawPath(path,p);}}}
    private final class ThemePreview extends View {
        Paint paint=new Paint(3);ThemePreview(Context c){super(c);setMinimumHeight(dp(74));setContentDescription("莫奈配色预览，设置自动应用");setLayoutParams(new LinearLayout.LayoutParams(-1,dp(74)));}
        float sp(float value){return value*getResources().getDisplayMetrics().scaledDensity;}
        protected void onDraw(Canvas canvas){
            super.onDraw(canvas);paint.setColor(SURFACE);canvas.drawRoundRect(0,0,getWidth(),dp(74),dp(15),dp(15),paint);
            boolean original=draft.mode.equals("original"),waiting=draft.mode.equals("auto")&&autoSeed==null;
            if(original||waiting){paint.setColor(FG);paint.setTextSize(sp(13));canvas.drawText(original?"系统原色":"正在读取表盘配色",dp(12),dp(31),paint);paint.setColor(MUTED);paint.setTextSize(sp(10));canvas.drawText(original?"使用系统原生颜色":"取色成功后显示预览",dp(12),dp(50),paint);return;}
            int seed=draft.mode.equals("fixed")?draft.seed:autoSeed;
            ThemePalettePreview p=ThemePalettePreview.fromSeed(seed);float rad=dp(draft.corners?9:3);
            for(int i=0;i<4;i++){paint.setColor(i==1||i==2?p.off:p.active);float x=dp(10+(i%2)*26),y=dp(10+(i/2)*26);canvas.drawRoundRect(x,y,x+dp(23),y+dp(23),rad,rad,paint);paint.setColor(i==1||i==2?p.onOff:p.onActive);canvas.drawCircle(x+dp(11.5f),y+dp(11.5f),dp(3),paint);}
            paint.setColor(FG);paint.setTextSize(sp(12));canvas.drawText(draft.mode.equals("fixed")?"固定配色":"表盘配色",dp(68),dp(31),paint);
            paint.setColor(p.secondary);paint.setTextSize(sp(10));canvas.drawText(String.format("强调 #%06X",p.active&0xffffff),dp(68),dp(49),paint);
        }
    }
}
