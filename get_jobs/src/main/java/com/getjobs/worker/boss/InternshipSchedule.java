package com.getjobs.worker.boss;

import org.json.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.*;

/** A local, single-flight queue. Codex only wakes the service each morning. */
@Service
public class InternshipSchedule {
    static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private final InternshipStore store;
    private final InternshipRunner runner;
    Clock clock=Clock.systemUTC();
    private boolean recoveryChecked;
    @org.springframework.beans.factory.annotation.Value("${internship.v2.enabled:false}") private boolean v2Enabled;
    public InternshipSchedule(InternshipStore store,InternshipRunner runner) {this.store=store;this.runner=runner;}

    public synchronized void setEnabled(boolean enabled) {
        if(v2Enabled)throw new IllegalStateException("开发版旧调度接口已停用");
        if(enabled&&runner.isRunning())throw new IllegalStateException("请先暂停本轮，再启用定时投递");
        if(enabled) {
            JSONObject c=store.settings();
            if(!c.optBoolean("confirmed") || c.optString("greeting").isBlank() || c.getJSONArray("keywords").isEmpty())
                throw new IllegalStateException("请先确认并保存资料、固定招呼语和搜索关键词");
        }
        ZonedDateTime now=ZonedDateTime.now(clock).withZoneSameInstant(ZONE);
        ZonedDateTime first=now.toLocalDate().atTime(7,0).atZone(ZONE);
        if(!now.isBefore(first))first=first.plusDays(1);
        String notBefore=first.toInstant().toString();
        store.updateSchedule(s->{
            s.put("enabled",enabled);
            if(enabled){s.put("notBefore",notBefore);s.remove("holdDate");s.remove("holdReason");}
            else s.put("pending",new JSONArray());
        });
        if(!enabled&&runner.isScheduledRun())runner.stop();
    }
    public java.util.Map<String,Object> status() {
        return store.scheduleState().put("timezone",ZONE.getId()).put("startHour",7).put("stopHour",17)
                .put("autoConfirmReviews",true).put("platform","both").put("roundMinutes",15).put("collectMinutes",10).put("sendUntilMinute",14).toMap();
    }
    @Scheduled(fixedDelay=5000)
    public synchronized void tick() {
        if(v2Enabled)return;
        ZonedDateTime now=ZonedDateTime.now(clock).withZoneSameInstant(ZONE);
        String day=now.toLocalDate().toString();JSONObject state=store.scheduleState();
        if(!recoveryChecked) {
            recoveryChecked=true;
            if(state.has("active")&&!runner.isRunning()) {
                JSONObject interrupted=state.getJSONObject("active");
                store.finishScheduled(interrupted.getString("id"),"上一轮异常中断，请核对发送记录后再启用定时投递",true,false,new JSONObject());
                state=store.scheduleState();
            }
        }
        if(!state.optString("day").equals(day)) {
            store.updateSchedule(s->{s.put("day",day).put("pending",new JSONArray()).put("scheduledSlots",new JSONArray()).put("completed",new JSONArray());s.remove("active");});
            state=store.scheduleState();
        }
        runner.checkScheduledDeadline();
        if(!state.optBoolean("enabled"))return;
        if(now.getHour()>=17) {
            if(!state.getJSONArray("pending").isEmpty())store.updateSchedule(s->s.put("pending",new JSONArray()));
            return;
        }
        if(now.getHour()<7 || now.toInstant().isBefore(Instant.parse(state.optString("notBefore",Instant.MAX.toString()))))return;
        ZonedDateTime slot=now.withMinute((now.getMinute()/15)*15).withSecond(0).withNano(0);
        String id=slot.toLocalDateTime().toString(),source=slot.getMinute()<30?"recommend":"search";
        String platform=slot.getMinute()%30==0?"boss":"shixiseng";
        boolean queued=false;for(Object known:state.getJSONArray("scheduledSlots"))if(id.equals(known.toString()))queued=true;
        if(!queued)store.updateSchedule(s->{s.getJSONArray("scheduledSlots").put(id);s.getJSONArray("pending").put(new JSONObject().put("id",id).put("source",source).put("platform",platform).put("deadline",slot.plusMinutes(15).toInstant().toString()));});
        if(runner.isRunning())return;
        // Expired slots are audited, never replayed into another platform's time budget.
        while(true) {
            JSONObject fresh=store.scheduleState();if(fresh.getJSONArray("pending").isEmpty())return;
            JSONObject candidate=fresh.getJSONArray("pending").getJSONObject(0);
            String p=candidate.optString("platform","boss");
            Instant end=slotEnd(candidate);
            JSONObject platformState=fresh.getJSONObject("platforms").getJSONObject(p);
            String reason=!now.toInstant().isBefore(end)?"时段已过，未补跑":
                    day.equals(platformState.optString("quotaDate"))?"该平台当日上限，未投递":
                    day.equals(platformState.optString("holdDate"))?"该平台已暂停："+platformState.optString("holdReason"):
                    p.equals("shixiseng")&&!store.shixisengState().optBoolean("liveSubmissionVerified")?"实习僧真实投递尚未验收，发送未启用":"";
            if(reason.isBlank())break;
            store.updateSchedule(s->{JSONObject skipped=s.getJSONArray("pending").getJSONObject(0);s.getJSONArray("pending").remove(0);
                s.getJSONArray("completed").put(skipped.put("status","skipped").put("message",reason).put("finishedAt",now.toInstant().toString()));});
        }
        JSONObject next=store.scheduleState().getJSONArray("pending").getJSONObject(0);
        store.updateSchedule(s->{s.getJSONArray("pending").remove(0);s.put("active",new JSONObject(next.toString()).put("startedAt",now.toInstant().toString()));});
        try {
            runner.startScheduled(next.optString("platform","boss"),next.getString("source"),next.getString("id"),slotEnd(next));
        } catch(Exception e) {
            store.finishScheduled(next.getString("id"),"定时启动失败："+e.getMessage(),true,false,new JSONObject());
        }
    }
    static Instant slotEnd(JSONObject slot) {
        return slot.has("deadline")?Instant.parse(slot.getString("deadline")):
                LocalDateTime.parse(slot.getString("id")).atZone(ZONE).plusMinutes(15).toInstant();
    }
}
