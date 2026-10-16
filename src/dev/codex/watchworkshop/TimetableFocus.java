package dev.codex.watchworkshop;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Pure calendar calculation. Boundaries are [start,end), in the instance's timezone. */
final class TimetableFocus {
    static final class Lesson {
        final TimetableData.Course course;final ZonedDateTime start,end;final int week;
        Lesson(TimetableData.Course c,ZonedDateTime start,ZonedDateTime end,int week){course=c;this.start=start;this.end=end;this.week=week;}
    }
    static final class State {
        final Lesson focus;final boolean ongoing,today;final String label,time,footer;final long nextChange;
        State(Lesson focus,boolean ongoing,boolean today,String label,String time,String footer,long next){this.focus=focus;this.ongoing=ongoing;this.today=today;this.label=label;this.time=time;this.footer=footer;nextChange=next;}
    }
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("HH:mm");
    static List<Lesson> lessons(TimetableData data,LocalDate date,ZoneId zone){
        ArrayList<Lesson> out=new ArrayList<>();
        if(data==null||data.semesterStart==null)return out;
        int week=data.week(date);if(week<1||week>data.totalWeeks)return out;
        HashMap<Integer,TimetableData.Slot> slots=new HashMap<>();for(TimetableData.Slot s:data.slotsFor(date))slots.put(s.number,s);
        for(TimetableData.Course c:data.courses){
            if(c.day!=date.getDayOfWeek().getValue()||!c.weeks.contains(week))continue;
            LocalTime start=c.customStart,end=c.customEnd;
            if(start==null){TimetableData.Slot s=slots.get(c.startSection),e=slots.get(c.endSection);if(s==null||e==null)continue;start=s.start;end=e.end;}
            ZonedDateTime from=date.atTime(start).atZone(zone),to=date.atTime(end).atZone(zone);
            if(to.isAfter(from))out.add(new Lesson(c,from,to,week));
        }
        Collections.sort(out,(a,b)->{int c=a.start.compareTo(b.start);return c!=0?c:a.course.name.compareTo(b.course.name);});
        return out;
    }
    static State at(TimetableData data,ZonedDateTime now){
        LocalDate today=now.toLocalDate();ZoneId zone=now.getZone();long next=today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        if(data==null||data.semesterStart==null)return new State(null,false,false,"尚未导入课表","","点按导入课程",Long.MAX_VALUE);
        LocalDate last=data.alignedStart().plusWeeks(data.totalWeeks).minusDays(1);
        LocalDate date=today.isBefore(data.alignedStart())?data.alignedStart():today;
        for(;!date.isAfter(last);date=date.plusDays(1)){
            List<Lesson> all=lessons(data,date,zone);
            for(int i=0;i<all.size();i++){
                Lesson l=all.get(i);if(!l.end.isAfter(now))continue;
                boolean same=date.equals(today),ongoing=same&&!l.start.isAfter(now);
                // Also wake for an earlier overlapping lesson's end, not only the chosen focus.
                for(Lesson day:all){if(day.start.isAfter(now))next=Math.min(next,day.start.toInstant().toEpochMilli());if(day.end.isAfter(now))next=Math.min(next,day.end.toInstant().toEpochMilli());}
                String dayLabel=dayLabel(today,date),label=ongoing?"上课中":same?(i==0?"今天 第一节":"下一节"):dayLabel+" 第一节";
                String time=(ongoing?l.end:l.start).format(TIME)+(ongoing?" 下课":" 上课");
                String footer;
                if(same&&(ongoing||i>0))footer=i+1<all.size()?(ongoing?"下一节 ":"之后 ")+all.get(i+1).start.format(TIME)+" "+all.get(i+1).course.name:"今天最后一节";
                else if(today.isBefore(data.semesterStart)&&ChronoUnit.DAYS.between(today,date)>7)
                    footer="距开学 "+ChronoUnit.DAYS.between(today,data.semesterStart)+" 天";
                else if(!same&&ChronoUnit.DAYS.between(today,date)>1&&ChronoUnit.DAYS.between(today.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),date)>=7)
                    footer="第 "+l.week+" 周 · "+(l.week%2==1?"单周":"双周");
                else footer=(same?"今天":dayLabel)+"共 "+all.size()+" 节 · "+all.get(all.size()-1).end.format(TIME)+" 结束";
                return new State(l,ongoing,same,label,time,footer,next);
            }
        }
        return new State(null,false,false,"本学期课程已结束","","点按导入新课表",next);
    }
    private static String dayLabel(LocalDate today,LocalDate date){
        long days=ChronoUnit.DAYS.between(today,date);if(days==1)return "明天";
        LocalDate monday=today.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        String weekday="一二三四五六日".substring(date.getDayOfWeek().getValue()-1,date.getDayOfWeek().getValue());
        if(date.isBefore(monday.plusWeeks(1)))return "周"+weekday;
        if(date.isBefore(monday.plusWeeks(2)))return "下周"+weekday;
        return date.getMonthValue()+"月"+date.getDayOfMonth()+"日";
    }
}
