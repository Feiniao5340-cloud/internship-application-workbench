package com.getjobs.worker.parallel;

import com.getjobs.worker.boss.*;
import com.getjobs.worker.manager.PlaywrightManager;
import jakarta.annotation.*;
import org.json.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.time.*;
import java.util.*;

@Service
public class ParallelWorkbench {
    private final ParallelRepository repository;
    private final InternshipRunner base;
    private final PlaywrightManager browser;
    private final InternshipModel model;
    private final Map<String,PlatformWorker> workers=new LinkedHashMap<>();
    private ParallelModelPool modelPool;
    private RoundDispatcher dispatcher;
    @Value("${internship.v2.enabled:false}") private boolean enabled;
    @Value("${internship.v2.live-enabled:false}") private boolean live;
    @Value("${internship.v2.sending-enabled:false}") private boolean sending;
    @Value("${internship.v2.model-enabled:false}") private boolean modeling;
    @Value("${internship.v2.protect-production-accounts:true}") private boolean protect;
    @Value("${internship.v2.notifications-enabled:false}") private boolean notifications;
    @Value("${internship.v2.dev-deadline:}") private String deadline="";
    private volatile boolean deadlineStopped;
    public Clock clock=Clock.systemUTC();
    public ParallelWorkbench(ParallelRepository repository,InternshipRunner base,PlaywrightManager browser,InternshipModel model){this.repository=repository;this.base=base;this.browser=browser;this.model=model;}
    @PostConstruct public void initialize() {
        if(!enabled)return;
        var notifier=InternshipFailureNotifier.isolated(Path.of("data","parallel","notifications"),Path.of("..","显示投递异常通知.ps1"),notifications);
        repository.onEvent(notifier::report);
        if(repository.document("_global","profile").isEmpty())repository.document("_global","profile",InternshipPolicy.defaults());
        JSONObject profile=profile();modelPool=new ParallelModelPool(model,2);
        for(String id:Platforms.IDS) {
            if(repository.document(id,"config").isEmpty())repository.document(id,"config",new JSONObject().put("enabled",false).put("sendEnabled",false).put("resumeReady",false)
                    .put("maxScan",profile.getInt("maxScan")).put("maxSend",profile.getInt("maxSend")).put("intervalSeconds",profile.getInt("intervalSeconds")).put("refreshRounds",profile.getInt("refreshRounds")).put("keywords",profile.getJSONArray("keywords")));
            workers.put(id,new PlatformWorker(id,repository,this::profile,base,browser,modelPool,live,sending,modeling,protect));
            if(id.equals("job51")&&!repository.document(id,"config").optString("siteAdapter").equals(InternshipYingjiesheng.ADAPTER)) {
                JSONObject c=repository.document(id,"config");c.put("siteAdapter",InternshipYingjiesheng.ADAPTER).put("resumeReady",false).put("sendEnabled",false).put("enabled",false);repository.document(id,"config",c);
                workers.get(id).store.updateSchedule(s->{JSONObject state=s.getJSONObject("platforms").getJSONObject(id);s.put("retired51jobState",new JSONObject(state.toString()));state.remove("holdDate");state.remove("holdReason");});
            }
        }
        Map<String,RoundDispatcher.Worker> dispatchWorkers=new LinkedHashMap<>();dispatchWorkers.putAll(workers);
        dispatcher=new RoundDispatcher(repository,dispatchWorkers,p->Platforms.available(p)&&repository.document(p,"config").optBoolean("enabled"));
    }
    public void requireWindow() {
        if(!enabled)throw new IllegalStateException("并行开发服务未启用");
        if(windowExpired())throw new IllegalStateException("本次开发窗口已结束，等待用户安排下一窗口；生产1.7.1继续运行");
    }
    private boolean windowExpired(){return !deadline.isBlank()&&!clock.instant().isBefore(OffsetDateTime.parse(deadline).toInstant());}
    public PlatformWorker worker(String id){Platforms.require(id);return workers.get(id);}
    public JSONObject profile(){return repository.document("_global","profile");}
    public synchronized void saveProfile(JSONObject update) {
        requireWindow();JSONObject c=profile();for(String key:update.keySet()){if(!c.has(key))throw new IllegalArgumentException("未知资料字段："+key);c.put(key,update.get(key));}
        InternshipPolicy.validateSettings(c);repository.document("_global","profile",c);
    }
    public synchronized void saveConfig(String id,JSONObject update) {
        requireWindow();PlatformWorker worker=worker(id);JSONObject config=worker.config();Set<String> allowed=Set.of("enabled","sendEnabled","resumeReady","maxScan","maxSend","intervalSeconds","refreshRounds","keywords","browserMode");
        if(update.has("browserMode")&&(!Set.of("headless","background").contains(update.getString("browserMode"))||(!Platforms.available(id)&&update.getString("browserMode").equals("background"))))throw new IllegalArgumentException("仅已接入的平台可使用普通Chrome后台运行");
        for(String key:update.keySet())if(!allowed.contains(key))throw new IllegalArgumentException("不能修改该平台字段："+key);
        if(!Platforms.available(id)&&(update.optBoolean("enabled")||update.optBoolean("sendEnabled")))throw new IllegalStateException("该平台已按用户要求停止接入");
        for(String key:update.keySet())config.put(key,update.get(key));
        for(String field:List.of("enabled","sendEnabled","resumeReady"))config.getBoolean(field);
        JSONObject validate=profile().put("platform",id);for(String field:List.of("maxScan","maxSend","intervalSeconds","refreshRounds","keywords"))validate.put(field,config.get(field));InternshipPolicy.validateSettings(validate);
        repository.document(id,"config",config);
    }
    public Map<String,Object> status() {
        Map<String,Object> result=new LinkedHashMap<>();result.put("name","实习投递工作台");result.put("version","2.1.0");result.put("distribution","portable-preview");
        result.put("development",true);result.put("liveEnabled",live);result.put("sendingEnabled",sending);result.put("modelEnabled",modeling);result.put("protectProductionAccounts",protect);
        result.put("notificationsEnabled",notifications);
        result.put("deadline",deadline);result.put("deadlineStopped",deadlineStopped||windowExpired());
        result.put("maxConcurrentPlatforms",3);result.put("model",modelPool==null?Map.of():modelPool.status());result.put("schedule",dispatcher==null?Map.of():dispatcher.state().toMap());
        result.put("platforms",workers.values().stream().filter(w->Platforms.available(w.id)).map(PlatformWorker::status).toList());return result;
    }
    public synchronized void enableSchedule(boolean value){requireWindow();dispatcher.setEnabled(value);}
    public synchronized void start(String id,String source){requireWindow();if(!Platforms.available(id))throw new IllegalStateException("该平台已按用户要求停止接入");if(worker(id).protectedAccount())throw new IllegalStateException("生产账号保护，开发版不运行旧版账号");dispatcher.enqueueManual(id,source);dispatcher.tick();}
    public synchronized void pause(String id){JSONObject config=worker(id).config();config.put("holdDate",LocalDate.now(clock.withZone(RoundDispatcher.ZONE)).toString()).put("holdReason","用户手动暂停该平台");repository.document(id,"config",config);dispatcher.pausePlatform(id);}
    public synchronized void resume(String id){requireWindow();PlatformWorker w=worker(id);if(w.running())throw new IllegalStateException("该平台仍在运行");String day=LocalDate.now(clock.withZone(RoundDispatcher.ZONE)).toString();JSONObject p=w.store.scheduleState().getJSONObject("platforms").getJSONObject(id);if(day.equals(p.optString("quotaDate")))throw new IllegalStateException("当日上限仍有效，不能恢复发送");JSONObject c=w.config();c.remove("holdDate");c.remove("holdReason");repository.document(id,"config",c);w.store.updateSchedule(s->{JSONObject target=s.getJSONObject("platforms").getJSONObject(id);target.remove("holdDate");target.remove("holdReason");});repository.event(id,day,"user_resume","用户恢复该平台；部分发送及当天去重记录未清除");}
    public synchronized void pauseAll(){if(dispatcher!=null){dispatcher.setEnabled(false);workers.values().forEach(PlatformWorker::stop);}}
    public Object checkSession(String id){requireWindow();if(!Platforms.available(id))throw new IllegalStateException("该平台已按用户要求停止接入");PlatformWorker w=worker(id);dispatcher.control(id,w::reserveControl);return w.checkReservedSession();}
    public Object browserDisplay(String id,boolean visible){requireWindow();if(!Platforms.available(id))throw new IllegalStateException("该平台已停止接入");PlatformWorker w=worker(id);dispatcher.control(id,w::reserveControl);return w.checkReservedSession(visible);}
    public Object diagnostic(String id,String url){requireWindow();PlatformWorker w=worker(id);dispatcher.control(id,w::reserveControl);return w.reservedDiagnostic(url);}
    public Object verifyReceipt(String id,String job,String url){requireWindow();if(!Set.of("zhilian","job51").contains(id))throw new IllegalArgumentException("该平台未提供站内回执核对");PlatformWorker w=worker(id);dispatcher.control(id,w::reserveControl);return w.verifyReservedReceipt(job,url);}
    public void continueNative(String id,String job){requireWindow();PlatformWorker w=worker(id);dispatcher.control(id,()->w.continueNative(job));}
    public JSONObject records(String id,String round) {
        PlatformWorker w=worker(id);String selected=round==null||round.isBlank()?w.store.roundSummary().optString("id"):round;
        JSONObject records=selected.isBlank()?new JSONObject():repository.candidates(id,selected);
        String current=InternshipPolicy.fingerprint(w.effective());for(String key:records.keySet()){JSONObject e=records.getJSONObject(key);e.put("assessmentCurrent",current.equals(e.optString("settingsHash")));e.put("manualConfirmed",InternshipPolicy.manuallyConfirmed(w.effective(),e));}
        return records;
    }
    public JSONArray rounds(String id){if(id!=null)Platforms.require(id);return repository.rounds(id);}
    public JSONArray events(){return repository.events();}
    public Object checkModel(){requireWindow();if(!modeling)throw new IllegalStateException("当前开发进程未开启模型");var result=modelPool.check(this::windowExpired);
        if(!((Map<?,?>)result.get("sample")).get("decision").equals("apply")||!((Map<?,?>)result.get("negativeSample")).get("decision").equals("skip"))throw new IllegalStateException("模型已返回，但岗位理解测试未通过");return result;}
    public void reassess(String id,String job){requireWindow();PlatformWorker w=worker(id);dispatcher.control(id,()->{w.store.saveSettings(w.effective());w.runner.reassess(job);});}
    public void deliver(String id,String job,boolean acceptance){requireWindow();if(!Platforms.available(id))throw new IllegalStateException("该平台已按用户要求停止接入");PlatformWorker w=worker(id);dispatcher.control(id,()->{String blocked=w.blocked(LocalDate.now(clock.withZone(RoundDispatcher.ZONE)).toString());if(!blocked.isBlank())throw new IllegalStateException(blocked);w.manualSend(job,acceptance);});}
    public void reconcile(String id,String job,JSONObject request){requireWindow();PlatformWorker w=worker(id);dispatcher.control(id,()->{String outcome=request.getString("outcome");w.store.reconcile(job,outcome,request.getString("note"),request.optBoolean("verified"));if(Set.of("unsent","image_only").contains(outcome))repository.resolveUnsent(id,job);else if(outcome.equals("complete"))repository.resolveConfirmed(id,job);});}
    @Scheduled(fixedDelay=1000) public void tick() {
        if(!enabled||dispatcher==null)return;
        if(windowExpired()) {
            if(!deadlineStopped){deadlineStopped=true;pauseAll();repository.event("_global","deadline","window_closed","用户指定的开发窗口已结束；生产1.7.1未改动。请等待下一次明确继续命令。");writeHandoff();}
            return;
        }
        for(PlatformWorker w:workers.values()){w.runner.checkScheduledDeadline();w.finishManualClaims();}
        dispatcher.clock=clock;dispatcher.tick();
    }
    private void writeHandoff() {
        try{Path p=Path.of("..","outputs","开发窗口截止交接.json");Files.createDirectories(p.toAbsolutePath().getParent());Files.writeString(p,new JSONObject(status()).put("message","到时暂停；禁止自动继续开发或切换生产").toString(2));}
        catch(Exception ignored){/* The persisted event remains authoritative if the report cannot be written. */}
    }
    @PreDestroy public void close(){workers.values().forEach(PlatformWorker::stop);for(PlatformWorker w:workers.values())try{w.close();}catch(Exception ignored){}}
}
