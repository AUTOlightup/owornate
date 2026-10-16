package dev.codex.watchworkshop;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;
import java.time.*;
import java.util.Map;
import org.json.*;

/**
 * 全局唯一的一份课表，所有课表卡片共用；移除卡片不会删掉课表。
 *
 * <p>解析结果按原始 JSON 缓存：上课期间卡片每分钟重画一次，同一份课表只解析一次，
 * 保存新课表后原始文本变了才重新解析。code10 及更早按卡片分别存的数据，第一次读取时搬一份过来。
 */
final class TimetableStore {
    private static final String SLOT="recent.";
    private static boolean migrated;
    private static String cachedRaw;
    private static TimetableData cachedData;
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences("timetable_widgets",0);}

    /** 全局槽还没有数据时，把旧的默认/逐卡片副本搬进来；每个进程只检查一次。 */
    private static synchronized void migrate(Context c){
        if(migrated)return;
        migrated=true;
        SharedPreferences p=prefs(c);
        if(p.contains(SLOT+"json"))return;
        String raw=p.getString("default.json",null),zone=p.getString("default.zone","system");
        if(raw==null)for(Map.Entry<String,?> entry:p.getAll().entrySet()){
            String key=entry.getKey();
            if(key.startsWith("widget.")&&key.endsWith(".json")&&entry.getValue() instanceof String){
                raw=(String)entry.getValue();zone=p.getString(key.substring(0,key.length()-4)+"zone","system");break;
            }
        }
        if(raw!=null)p.edit().putString(SLOT+"json",raw).putString(SLOT+"zone",zone).commit();
    }
    static String readJson(Context c){migrate(c);return prefs(c).getString(SLOT+"json",null);}
    /** 已保存的课表；没有导入过时为 null。 */
    static synchronized TimetableData read(Context c)throws Exception {
        String raw=readJson(c);
        if(raw==null)return null;
        if(!raw.equals(cachedRaw)){cachedData=ShiguangJson.parse(raw,null);cachedRaw=raw;}
        return cachedData;
    }
    static ZoneId zone(Context c){migrate(c);String zone=prefs(c).getString(SLOT+"zone","system");return "system".equals(zone)?ZoneId.systemDefault():ZoneId.of(zone);}
    static synchronized void save(Context c,String raw,LocalDate override,String zone)throws Exception {
        if(raw==null||raw.getBytes("UTF-8").length>1024*1024)throw new IOException("请选择不超过 1 MB 的拾光课表 JSON");
        TimetableData old=null;try{old=read(c);}catch(Exception ignored){}
        TimetableData parsed=ShiguangJson.parse(raw,old);
        LocalDate start=override==null?parsed.semesterStart:override;
        if(start==null)throw new IOException("文件未含开学日期，请填写第 1 周的日期");
        if(!"system".equals(zone))ZoneId.of(zone);
        JSONObject normalized=new JSONObject(raw);JSONObject config=normalized.optJSONObject("config");if(config==null)config=new JSONObject();
        config.put("semesterStartDate",start.toString()).put("semesterTotalWeeks",parsed.totalWeeks).put("firstDayOfWeek",parsed.firstDayOfWeek);
        normalized.put("config",config);
        // Missing upstream timeSlots means retain the old table; persist the resolved slots too.
        JSONArray slots=new JSONArray();for(TimetableData.Slot s:parsed.slots)slots.put(new JSONObject().put("number",s.number).put("startTime",s.start.toString()).put("endTime",s.end.toString()));
        normalized.put("timeSlots",slots);
        ShiguangJson.parse(normalized.toString(),null);
        if(!prefs(c).edit().putString(SLOT+"json",normalized.toString()).putString(SLOT+"zone",zone).putBoolean(SLOT+"initialized",true).commit())throw new IOException("课表未保存，请重试");
    }
}
