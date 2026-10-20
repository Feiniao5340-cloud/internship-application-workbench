package com.getjobs.worker.boss;

import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;

/** Private runtime data, never packaged with the application. */
@Component
public class InternshipStore {
    @org.springframework.beans.factory.annotation.Autowired(required=false) protected InternshipFailureNotifier failureNotifier;
    private final Path directory;
    java.time.Clock clock=java.time.Clock.systemUTC();
    public InternshipStore() { this(Path.of("data", "internship")); }
    public InternshipStore(Path directory) { this.directory = directory; }

    public synchronized JSONObject settings() {
        JSONObject c = InternshipPolicy.defaults();
        JSONObject saved = read("settings.json");
        for (String k : saved.keySet()) c.put(k, saved.get(k));
        InternshipPolicy.validateSettings(c);
        return c;
    }
    public synchronized void saveSettings(JSONObject c) {
        InternshipPolicy.validateSettings(c);
        write("settings.json", c);
    }
    public synchronized JSONObject scheduleState() {
        JSONObject state=new JSONObject().put("enabled",false).put("day","").put("pending",new JSONArray())
                .put("scheduledSlots",new JSONArray()).put("completed",new JSONArray());
        JSONObject saved=read("schedule.json");for(String k:saved.keySet())state.put(k,saved.get(k));
        JSONObject platforms=state.optJSONObject("platforms");if(platforms==null){platforms=new JSONObject();state.put("platforms",platforms);}
        if(!platforms.has("boss")) {
            JSONObject boss=new JSONObject();for(String k:java.util.List.of("holdDate","holdReason","quotaDate","quotaReason"))if(state.has(k))boss.put(k,state.get(k));
            platforms.put("boss",boss);
        }
        if(!platforms.has("shixiseng")) {
            JSONObject nativeState=shixisengState(),sx=new JSONObject();
            if(nativeState.has("quotaDate"))sx.put("quotaDate",nativeState.get("quotaDate")).put("quotaReason",nativeState.optString("reason"));
            platforms.put("shixiseng",sx);
        }
        for(String platform:java.util.List.of("zhilian","job51","lagou"))
            if(!platforms.has(platform))platforms.put(platform,new JSONObject());
        return state;
    }
    public synchronized JSONObject shixisengState() {return read("shixiseng.json");}
    public synchronized void saveShixisengState(JSONObject state) {write("shixiseng.json",state);}
    public synchronized void updateSchedule(java.util.function.Consumer<JSONObject> update) {
        JSONObject state=scheduleState();update.accept(state);write("schedule.json",state);
    }
    public synchronized void holdSchedule(String day,String reason) {
        holdPlatform("boss",day,reason);
    }
    public synchronized void holdPlatform(String platform,String day,String reason) {
        updateSchedule(s->{s.getJSONObject("platforms").getJSONObject(platform).put("holdDate",day).put("holdReason",reason);
            if(platform.equals("boss"))s.put("holdDate",day).put("holdReason",reason);});
    }
    public synchronized void scheduleQuotaReached(String day,String reason) {
        platformQuotaReached("boss",day,reason);
    }
    public synchronized void platformQuotaReached(String platform,String day,String reason) {
        boolean already=day.equals(scheduleState().getJSONObject("platforms").getJSONObject(platform).optString("quotaDate"));
        updateSchedule(s->{s.getJSONObject("platforms").getJSONObject(platform).put("quotaDate",day).put("quotaReason",reason);
            if(platform.equals("boss"))s.put("quotaDate",day).put("quotaReason",reason);});
        if(!already&&failureNotifier!=null)failureNotifier.report("daily_limit",platform.equals("boss")?day:"实习僧 · "+day,reason+"；该平台今天已停止投递，另一平台不受影响");
    }
    public synchronized void finishScheduled(String slot,String message,boolean paused,boolean deadline,JSONObject summary) {
        JSONObject before=scheduleState();
        boolean wasActive=before.has("active")&&before.getJSONObject("active").optString("id").equals(slot);
        String platform=wasActive?before.getJSONObject("active").optString("platform","boss"):"boss";
        String label=com.getjobs.worker.parallel.Platforms.label(platform);
        if(wasActive&&paused&&!deadline&&(InternshipPolicy.dailyCommunicationLimit(message)||message.contains("当日投递上限"))) {
            platformQuotaReached(platform,before.optString("day"),message);before=scheduleState();
        }
        updateSchedule(s->{
            JSONObject active=s.optJSONObject("active");
            if(active==null || !active.optString("id").equals(slot))return;
            active.put("finishedAt",Instant.now().toString()).put("status",paused?"paused":"finished")
                    .put("message",message).put("summary",summary);
            s.getJSONArray("completed").put(active);s.remove("active");
            if(paused&&!deadline) {
                s.getJSONObject("platforms").getJSONObject(platform).put("holdDate",s.optString("day")).put("holdReason",message);
                if(platform.equals("boss"))s.put("holdDate",s.optString("day")).put("holdReason",message);
            }
        });
        if(wasActive&&paused&&!deadline&&!message.contains("暂停请求已收到")&&!message.contains("用户手动暂停")
                &&!slot.startsWith(before.getJSONObject("platforms").getJSONObject(platform).optString("quotaDate","__no_quota__"))&&failureNotifier!=null)
            failureNotifier.report("round_failed",slot,label+"："+message+"；该平台今日已暂停，另一平台继续运行");
    }
    public synchronized JSONObject records() { return read("records.json"); }
    public synchronized JSONObject candidateRecords() {
        JSONObject visible=new JSONObject(),all=records();
        for(String id:all.keySet()) {
            JSONObject r=all.getJSONObject(id);
            if(!r.optBoolean("excludedFromPool") && (attempted(r) || InternshipPolicy.formalReason(r.getJSONObject("job")).isBlank()))visible.put(id,r);
        }
        return visible;
    }
    public synchronized String beginRound(int limit) {
        String id=java.util.UUID.randomUUID().toString();
        write("round.json",new JSONObject().put("id",id).put("startedAt",Instant.now().toString())
                .put("state","running").put("limit",limit).put("preExcluded",0));return id;
    }
    public synchronized void endRound(String state) {
        JSONObject round=read("round.json");if(round.isEmpty())return;
        round.put("state",state).put("endedAt",Instant.now().toString());write("round.json",round);
    }
    public synchronized JSONObject readFailures(){return read("read-failures.json");}
    public synchronized void excludeFormalListing(){JSONObject round=read("round.json");if(!round.isEmpty()){round.put("preExcluded",round.optInt("preExcluded")+1);write("round.json",round);}}
    public synchronized void recordReadFailure(String url,String reason) {
        JSONObject round=read("round.json"),failures=readFailures();String key=InternshipPolicy.hash(url),roundId=round.optString("id");
        if(failures.has(key)&&failures.getJSONObject(key).optString("roundId").equals(roundId))return;
        JSONObject failure=new JSONObject().put("url",url).put("reason",reason).put("roundId",roundId).put("at",Instant.now().toString());
        failures.put(key,failure);
        while(failures.length()>100){String oldest=failures.keySet().stream().min(java.util.Comparator.comparing(k->failures.getJSONObject(k).optString("at"))).orElseThrow();failures.remove(oldest);}
        write("read-failures.json",failures);
        if(!round.isEmpty()){round.put("readFailed",round.optInt("readFailed")+1).put("lastReadFailure",failure);write("round.json",round);}
    }
    public synchronized JSONObject roundSummary() {
        JSONObject round=read("round.json");if(round.isEmpty())return round;
        int total=0,apply=0,skip=0,review=0;
        JSONObject all=candidateRecords();
        for(String id:all.keySet()) {
            JSONObject entry=all.getJSONObject(id);if(!entry.optString("roundId").equals(round.getString("id")))continue;
            total++;switch(entry.getJSONObject("result").optString("decision")) {
                case "apply" -> apply++;case "skip" -> skip++;default -> review++;
            }
        }
        return round.put("total",total).put("apply",apply).put("skip",skip).put("review",review);
    }
    public synchronized void exclude(String id,JSONObject job,String reason,String roundId) {
        JSONObject exclusions=read("excluded.json");
        boolean already=exclusions.has(id) && exclusions.getJSONObject(id).optString("roundId").equals(roundId);
        exclusions.put(id,new JSONObject().put("job",job).put("reason",reason).put("roundId",roundId).put("at",Instant.now().toString()));
        write("excluded.json",exclusions);
        JSONObject prior=get(id);
        if(prior!=null && !attempted(prior)) {
            prior.put("excludedFromPool",true).put("approved",false).put("preExcludedReason",reason);save(id,prior);
        }
        JSONObject round=read("round.json");
        if(!already && round.optString("id").equals(roundId)) {round.put("preExcluded",round.optInt("preExcluded")+1);write("round.json",round);}
    }
    public synchronized int archiveFormalRecords() {
        int count=0;JSONObject all=records();
        for(String id:all.keySet()) {
            JSONObject r=all.getJSONObject(id);String reason=InternshipPolicy.formalReason(r.getJSONObject("job"));
            if(!attempted(r) && !r.optBoolean("excludedFromPool") && !reason.isBlank()) {exclude(id,r.getJSONObject("job"),reason,"historical");count++;}
        }
        return count;
    }
    public synchronized JSONObject get(String id) { return records().optJSONObject(id); }
    public synchronized void save(String id, JSONObject entry) {
        JSONObject all = records();
        JSONObject previous = all.optJSONObject(id);
        if(previous != null && previous.has("reconciliation") && !entry.has("reconciliation"))
            entry.put("reconciliation", previous.get("reconciliation"));
        JSONArray history = previous == null ? new JSONArray() : previous.optJSONArray("history");
        if (history == null) history = new JSONArray();
        if (previous == null || !previous.optString("status").equals(entry.optString("status")))
            history.put(new JSONObject().put("at", clock.instant().toString()).put("status", entry.optString("status")));
        entry.put("history", history);
        entry.put("updatedAt", clock.instant().toString());
        all.put(id, entry);
        write("records.json", all);
    }
    public synchronized void approve(String id) {
        JSONObject r = get(id);
        if (r == null || r.optBoolean("excludedFromPool") || !InternshipPolicy.formalReason(r.optJSONObject("job")==null?new JSONObject():r.getJSONObject("job")).isBlank()
                || !java.util.Set.of("screened","ready_image").contains(r.optString("status"))
                || !InternshipPolicy.deliveryEligible(settings(),r)
                || !r.optString("settingsHash").equals(InternshipPolicy.fingerprint(settings())))
            throw new IllegalArgumentException("请使用当前资料重新判断；待确认岗位须先填写人工确认依据");
        r.put("approved", true);
        save(id, r);
    }
    public synchronized void confirmReview(String id,String note) {
        confirmReview(id,note,"manual");
    }
    public synchronized void confirmReview(String id,String note,String origin) {
        JSONObject r=get(id),c=settings();
        if(note==null || note.isBlank() || note.length()>2000)throw new IllegalArgumentException("请填写人工确认依据（最多2000字）");
        if(r==null || r.optBoolean("excludedFromPool") || !java.util.Set.of("screened","ready_image").contains(r.optString("status"))
                || !r.getJSONObject("result").optString("decision").equals("review")
                || !r.optString("settingsHash").equals(InternshipPolicy.fingerprint(c)))
            throw new IllegalArgumentException("仅能人工确认当前资料下尚未完成投递的待确认岗位，请先重新判断");
        String blocked=InternshipPolicy.manualReviewBlockReason(c,r.getJSONObject("job"));
        if(!blocked.isBlank())throw new IllegalArgumentException("硬性条件未通过，不能人工放行："+blocked);
        r.put("humanReview",new JSONObject().put("note",note.trim()).put("origin",origin).put("at",Instant.now().toString())
                .put("settingsHash",InternshipPolicy.fingerprint(c)).put("jobHash",InternshipPolicy.hash(r.getJSONObject("job").toString())));
        r.put("approved",false);save(id,r);
    }
    public static boolean attempted(JSONObject r) {
        return r != null && !r.optString("status", "screened").equals("screened");
    }
    /** Last confirmed completion, using Beijing's calendar day; unknown dates stay locked. */
    private String completionDay(JSONObject entry) {
        if(entry==null)return "";
        String at="";JSONArray history=entry.optJSONArray("history");
        if(history!=null)for(Object item:history) {JSONObject h=(JSONObject)item;
            if(h.optString("status").equals("complete"))at=h.optString("at");}
        if(at.isBlank()&&entry.optString("status").equals("complete"))at=entry.optString("updatedAt");
        try {return Instant.parse(at).atZone(InternshipSchedule.ZONE).toLocalDate().toString();}
        catch(Exception invalidDate) {return "";}
    }
    public synchronized boolean completedBeforeToday(JSONObject entry) {
        String day=completionDay(entry);
        return !day.isBlank()&&day.compareTo(clock.instant().atZone(InternshipSchedule.ZONE).toLocalDate().toString())<0;
    }
    public synchronized boolean completedToday(JSONObject entry) {
        String day=completionDay(entry);
        return !day.isBlank()&&day.equals(clock.instant().atZone(InternshipSchedule.ZONE).toLocalDate().toString());
    }
    public synchronized boolean blocksRetry(JSONObject entry) {
        if(entry==null)return false;
        if(completedToday(entry))return true;
        return attempted(entry)&&!entry.optString("status").equals("ready_image")
                &&!(entry.optString("status").equals("complete")&&completedBeforeToday(entry));
    }
    public synchronized void reconcile(String id, String outcome, String note, boolean verified) {
        JSONObject r=get(id);
        if(r!=null&&r.optJSONObject("job")!=null&&!r.getJSONObject("job").optString("platform","boss").equals("boss")&&outcome.equals("image_only"))
            throw new IllegalArgumentException("实习僧使用站内简历投递，不适用补发聊天图片");
        if (!verified || note.isBlank() || r==null || !attempted(r) || r.optString("status").equals("complete"))
            throw new IllegalArgumentException("请核对真实会话并填写依据；仅能处理部分发送记录");
        String status=switch(outcome) {
            case "complete" -> "complete";
            case "image_only" -> "ready_image";
            case "unsent" -> "screened";
            default -> throw new IllegalArgumentException("核对结果无效");
        };
        r.put("status",status).put("reconciliation",new JSONObject().put("note",note).put("at",Instant.now().toString()).put("outcome",outcome));
        r.put("approved",false);
        if(outcome.equals("complete")) {
            if(r.optJSONObject("job")!=null&&!r.getJSONObject("job").optString("platform","boss").equals("boss"))r.put("nativeResumeConfirmed",true);
            else r.put("textConfirmed",true).put("imageConfirmed",true);
        }
        if(outcome.equals("image_only")) r.put("textConfirmed",true).put("imageConfirmed",false);
        if(outcome.equals("unsent")) r.put("textConfirmed",false).put("imageConfirmed",false);
        save(id,r);
    }
    protected JSONObject read(String name) {
        try { return Files.exists(directory.resolve(name)) ? new JSONObject(Files.readString(directory.resolve(name))) : new JSONObject(); }
        catch (Exception e) { throw new IllegalStateException("私有数据读取失败，请保留文件并检查：" + name, e); }
    }
    protected void write(String name, JSONObject value) {
        try {
            Files.createDirectories(directory);
            Path temp = Files.createTempFile(directory, name, ".tmp");
            Files.writeString(temp, value.toString(2));
            try { Files.move(temp, directory.resolve(name), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, directory.resolve(name), StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException e) { throw new IllegalStateException("状态无法保存，停止投递", e); }
    }
}
