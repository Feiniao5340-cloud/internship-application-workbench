package com.getjobs.worker.parallel;

import org.json.*;
import java.time.*;
import java.util.*;

/** Clock-driven, single coordinator; browser workers are independent of this lock. */
public class RoundDispatcher {
    public static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    public interface Worker {
        boolean running();
        String blocked(String day);
        void start(JSONObject job);
        void stop();
        JSONObject outcome(JSONObject job);
    }
    private final ParallelRepository repository;
    private final Map<String,Worker> workers;
    private final java.util.function.Predicate<String> enabled;
    private boolean recovered;
    public Clock clock=Clock.systemUTC();
    public RoundDispatcher(ParallelRepository repository,Map<String,Worker> workers,java.util.function.Predicate<String> enabled){this.repository=repository;this.workers=workers;this.enabled=enabled;}
    public synchronized JSONObject state() {
        JSONObject s=new JSONObject().put("enabled",false).put("pending",new JSONArray()).put("active",new JSONObject()).put("completed",new JSONArray()).put("scheduledSlots",new JSONArray()).put("rotation",0).put("day","");
        JSONObject saved=repository.document("_global","dispatch");for(String k:saved.keySet())s.put(k,saved.get(k));return s;
    }
    private void save(JSONObject s){repository.document("_global","dispatch",s);}
    public synchronized void setEnabled(boolean enabled) {
        JSONObject s=state();s.put("enabled",enabled);if(!enabled){s.put("pending",new JSONArray());for(String p:s.getJSONObject("active").keySet())workers.get(p).stop();}save(s);
    }
    public synchronized void enqueueManual(String platform,String source) {
        if(!Set.of("recommend","search").contains(source))throw new IllegalArgumentException("来源无效");
        JSONObject s=state();if(s.getJSONObject("active").has(platform)||workers.get(platform).running())throw new IllegalStateException("该平台已在运行");
        for(Object item:s.getJSONArray("pending"))if(((JSONObject)item).getString("platform").equals(platform))throw new IllegalStateException("该平台已有等待任务");
        Instant now=clock.instant();s.getJSONArray("pending").put(new JSONObject().put("id",now.atZone(ZONE).toLocalDateTime()+"|"+platform+"|manual").put("platform",platform).put("source",source).put("manual",true).put("triggeredAt",now.toString()).put("deadline",now.plusSeconds(1800).toString()));save(s);
    }
    public synchronized void tick() {
        ZonedDateTime now=clock.instant().atZone(ZONE);String day=now.toLocalDate().toString();JSONObject s=state();
        JSONObject active=s.getJSONObject("active");
        if(!recovered) {
            recovered=true;
            for(String p:new ArrayList<>(active.keySet()))if(!workers.get(p).running()) {
                JSONObject job=active.getJSONObject(p).put("status","interrupted").put("message","开发服务上次异常中断，待核对发送记录");
                s.getJSONArray("completed").put(job);repository.event(p,job.getString("id"),"round_failed",job.getString("message"));
                JSONObject config=repository.document(p,"config");config.put("holdDate",day).put("holdReason",job.getString("message"));repository.document(p,"config",config);active.remove(p);
            }
        }
        for(String p:new ArrayList<>(active.keySet()))if(!workers.get(p).running()) {
            JSONObject job=active.getJSONObject(p),outcome=workers.get(p).outcome(job);
            job.put("finishedAt",clock.instant().toString()).put("outcome",outcome).put("status",outcome.optBoolean("failed")?"failed":"finished");
            s.getJSONArray("completed").put(job);active.remove(p);
        }
        if(!s.optString("day").equals(day)) {
            JSONArray retained=new JSONArray();
            for(Object item:s.getJSONArray("pending")) {
                JSONObject job=(JSONObject)item;
                if(job.optBoolean("manual")&&clock.instant().isBefore(Instant.parse(job.getString("deadline"))))retained.put(job);
            }
            s.put("day",day).put("pending",retained).put("scheduledSlots",new JSONArray()).put("completed",new JSONArray());
        }
        if(s.optBoolean("enabled")&&now.getHour()>=7&&now.getHour()<17) {
            ZonedDateTime slot=now.withMinute(now.getMinute()<30?0:30).withSecond(0).withNano(0);
            String source=slot.getMinute()==0?"recommend":"search";
            List<String> order=new ArrayList<>(Platforms.IDS);Collections.rotate(order,-s.optInt("rotation"));boolean added=false;
            Set<Object> known=new HashSet<>(s.getJSONArray("scheduledSlots").toList());
            for(String platform:order) {
                String id=slot.toLocalDateTime()+"|"+platform;
                if(!known.contains(id)&&enabled.test(platform)) {
                    s.getJSONArray("scheduledSlots").put(id);s.getJSONArray("pending").put(new JSONObject().put("id",id).put("platform",platform).put("source",source).put("triggeredAt",slot.toInstant().toString()).put("deadline",slot.plusMinutes(30).toInstant().toString()));added=true;
                }
            }
            if(added)s.put("rotation",(s.optInt("rotation")+1)%Platforms.IDS.size());
        }
        JSONArray pending=s.getJSONArray("pending");
        for(int i=0;i<pending.length();) {
            JSONObject job=pending.getJSONObject(i);String platform=job.getString("platform"),blocked=workers.get(platform).blocked(day);
            if(!job.optBoolean("manual")&&!enabled.test(platform)) {
                job.put("status","skipped").put("message","该平台设置已停用，不再启动排队轮次").put("finishedAt",clock.instant().toString());
                s.getJSONArray("completed").put(job);pending.remove(i);continue;
            }
            boolean expired=!clock.instant().isBefore(Instant.parse(job.getString("deadline")))||(!job.optBoolean("manual")&&now.getHour()>=17);
            if(expired||!blocked.isBlank()) {
                job.put("status",expired?"expired":"skipped").put("message",expired?"时段已过，未补跑":blocked).put("finishedAt",clock.instant().toString());s.getJSONArray("completed").put(job);pending.remove(i);
                if(expired&&(job.optBoolean("manual")||now.getHour()<17))repository.event(platform,job.getString("id"),"round_expired","排队时段已过，未运行；请检查平台耗时与并发容量");
                continue;
            }
            long occupied=workers.entrySet().stream().filter(e->active.has(e.getKey())||e.getValue().running()).count();
            if(occupied>=3||active.has(platform)||workers.get(platform).running()){i++;continue;}
            if(!job.optBoolean("manual")&&!s.optBoolean("enabled")){i++;continue;}
            Instant start=clock.instant(),slotDeadline=Instant.parse(job.getString("deadline"));
            Instant sliceDeadline=start.plusSeconds(14*60);
            if(sliceDeadline.isAfter(slotDeadline))sliceDeadline=slotDeadline;
            pending.remove(i);job.put("startedAt",start.toString()).put("slotDeadline",slotDeadline.toString()).put("deadline",sliceDeadline.toString());active.put(platform,job);
            // Persist ownership before a worker can make any external side effect.
            save(s);
            try{workers.get(platform).start(job);}
            catch(Exception e){active.remove(platform);job.put("status","failed").put("message",e.getMessage());s.getJSONArray("completed").put(job);repository.event(platform,job.getString("id"),"round_failed",e.getMessage());
                JSONObject config=repository.document(platform,"config");config.put("holdDate",day).put("holdReason",e.getMessage());repository.document(platform,"config",config);}
        }
        save(s);
    }
    public synchronized void pausePlatform(String platform) {
        workers.get(platform).stop();JSONObject s=state();JSONArray pending=s.getJSONArray("pending");for(int i=pending.length()-1;i>=0;i--)if(pending.getJSONObject(i).getString("platform").equals(platform))pending.remove(i);save(s);
    }
    /** Reserve a short control/launch operation under the same capacity lock as scheduled starts. */
    public synchronized void control(String platform,Runnable launch) {
        JSONObject s=state(),active=s.getJSONObject("active");
        if(active.has(platform)||workers.get(platform).running())throw new IllegalStateException("该平台正在运行");
        for(Object item:s.getJSONArray("pending"))if(((JSONObject)item).getString("platform").equals(platform))throw new IllegalStateException("该平台正在排队，请先暂停排队任务");
        long occupied=workers.entrySet().stream().filter(e->active.has(e.getKey())||e.getValue().running()).count();
        if(occupied>=3)throw new IllegalStateException("已有三个平台运行，请稍后操作");
        launch.run();
    }
}
