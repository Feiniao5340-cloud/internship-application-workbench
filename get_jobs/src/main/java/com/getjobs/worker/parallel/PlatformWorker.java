package com.getjobs.worker.parallel;

import com.getjobs.worker.boss.*;
import com.getjobs.worker.manager.PlaywrightManager;
import org.json.*;
import java.time.*;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;

public class PlatformWorker implements RoundDispatcher.Worker {
    public final String id;
    public final PlatformStore store;
    public final InternshipRunner runner;
    public final PlaywrightManager browser;
    public final InternshipNativeSites nativeSites;
    private final ParallelRepository repository;
    private final Supplier<JSONObject> profile;
    private final boolean live,sending,modeling,protect;
    private final Map<String,String> manualClaims=new HashMap<>();
    private final Map<String,String> automaticClaimDays=new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean controlBusy=new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean manualBrowser;
    private volatile Map<String,Object> session=Map.of("loggedIn",false,"message","尚未检查独立登录态");
    public PlatformWorker(String id,ParallelRepository repository,Supplier<JSONObject> profile,InternshipRunner base,PlaywrightManager baseBrowser,InternshipModel model,boolean live,boolean sending,boolean modeling,boolean protect) {
        this.id=Platforms.require(id);this.repository=repository;this.profile=profile;this.live=live;this.sending=sending;this.modeling=modeling;this.protect=protect;
        store=new PlatformStore(repository,id);browser=baseBrowser.isolated(id,Path.of("data","parallel","browsers",id),live,protect);
        nativeSites=id.equals("job51")?new InternshipYingjiesheng(store,this::config):new InternshipNativeSites(store,id,this::config);
        runner=base.isolated(store,browser,model,live,sending,modeling,nativeSites,new InternshipRunner.DeliveryGate(){
            public boolean claim(String job,JSONObject entry){String day=today();if(!repository.claim(id,"default",job,day))return false;automaticClaimDays.put(job,day);return true;}
            public void finish(String job,JSONObject entry){String day=automaticClaimDays.remove(job);if(day!=null)repository.finishClaim(id,"default",job,day,entry==null?"native_attempted":entry.optString("status","screened"));}
        });
    }
    private static String today(){return LocalDate.now(RoundDispatcher.ZONE).toString();}
    public JSONObject config(){return repository.document(id,"config");}
    boolean backgroundBrowser(){return Platforms.available(id)&&config().optString("browserMode","background").equals("background");}
    private void prepareAutomaticBrowser(){boolean background=backgroundBrowser();browser.setHeadlessMode(!background);browser.setBackgroundMode(background);}
    public boolean protectedAccount(){return protect&&Platforms.protectedAccount(id);}
    public JSONObject effective() {
        JSONObject c=new JSONObject(profile.get().toString()),config=config();c.put("platform",id);
        if(id.equals("job51"))c.put("siteAdapter",InternshipYingjiesheng.ADAPTER);
        for(String key:List.of("maxScan","maxSend","intervalSeconds","refreshRounds","keywords"))if(config.has(key))c.put(key,config.get(key));
        boolean ready=config.optBoolean("resumeReady"),accepted=liveVerified();
        if(id.equals("shixiseng"))c.put("shixisengResumeConfirmed",ready);
        c.put("mode",sending&&config.optBoolean("sendEnabled")&&ready&&accepted&&!protectedAccount()?"auto":"scan");
        InternshipPolicy.validateSettings(c);return c;
    }
    @Override public boolean running(){return runner.isRunning()||controlBusy.get();}
    public boolean liveVerified(){
        if(id.equals("shixiseng"))return store.shixisengState().optBoolean("liveSubmissionVerified");
        JSONObject proof=store.scheduleState().optJSONObject(id.equals("boss")?"bossAcceptance":"nativeAcceptance");
        return proof!=null&&proof.optBoolean("liveSubmissionVerified")&&(!id.equals("job51")||proof.optString("adapter").equals(InternshipYingjiesheng.ADAPTER));
    }
    @Override public String blocked(String day) {
        if(manualBrowser)return "浏览器正在人工处理；请点击返回无界面运行后再执行轮次";
        JSONObject config=config();if(day.equals(config.optString("holdDate")))return config.optString("holdReason");
        JSONObject state=store.scheduleState().getJSONObject("platforms").getJSONObject(id);
        if(day.equals(state.optString("quotaDate")))return "该平台今日已达到上限";
        if(day.equals(state.optString("holdDate")))return "该平台今日暂停："+state.optString("holdReason");return "";
    }
    @Override public void start(JSONObject job) {
        if(protectedAccount())throw new IllegalStateException("生产账号保护：本次开发不登录或投递旧版BOSS/实习僧账号");
        if(manualBrowser)throw new IllegalStateException("请先返回无界面运行");
        prepareAutomaticBrowser();
        if(job.optInt("retryAttempt")==1)prepareRetry(job);
        JSONObject c=effective().put("source",job.getString("source"));store.saveSettings(c);
        if(job.optInt("retryAttempt")==1) {
            long sent=store.records().toMap().values().stream().map(v->new JSONObject((Map<?,?>)v)).filter(e->e.optString("roundId").equals(job.optString("retryRoundId"))&&e.optString("status").equals("complete")).count();
            int remaining=c.getInt("maxSend")-(int)sent;if(remaining<=0)c.put("mode","scan");else c.put("maxSend",remaining);store.saveSettings(c);
        }
        store.updateSchedule(s->s.put("day",today()).put("active",new JSONObject(job.toString())));
        runner.startConfiguredRound(c,job.getString("id"),Instant.parse(job.getString("deadline")));
    }
    private void prepareRetry(JSONObject job) {
        String day=today();JSONObject config=config(),state=store.scheduleState().getJSONObject("platforms").getJSONObject(id);
        String reason=blocked(day);
        if(day.equals(state.optString("quotaDate"))||reason.contains("用户手动暂停")||reason.contains("人工处理"))throw new IllegalStateException("人工暂停、验证处理或当日上限仍有效，未自动恢复");
        JSONObject entries=store.records();
        for(String key:entries.keySet()) {
            JSONObject entry=entries.getJSONObject(key);
            if(!entry.optString("roundId").equals(job.optString("retryRoundId")))continue;
            if(!store.blocksRetry(entry)||entry.optString("status").equals("complete"))continue;
            // Never replay an uncertain submission. The retry may only read its receipt.
            if(!id.equals("zhilian")||!entry.optString("status").equals("native_attempted"))throw new IllegalStateException("存在未确认的发送，自动重试仅核对回执，未重新发送");
            browser.ensureReady();browser.callOnPlaywright(()->nativeSites.verifyExistingReceipt(key,entry,browser.getBossPage(),entry.getJSONObject("job").optString("navigationUrl",entry.getJSONObject("job").getString("url"))));
            repository.resolveConfirmed(id,key);
        }
        config.remove("holdDate");config.remove("holdReason");repository.document(id,"config",config);
        store.updateSchedule(s->{JSONObject target=s.getJSONObject("platforms").getJSONObject(id);target.remove("holdDate");target.remove("holdReason");});
    }
    @Override public void stop(){runner.stop();}
    @Override public JSONObject outcome(JSONObject job) {
        JSONObject result=new JSONObject().put("message",runner.status().get("progress")).put("summary",store.roundSummary());
        for(Object item:store.scheduleState().getJSONArray("completed")) {JSONObject completed=(JSONObject)item;if(completed.optString("id").equals(job.getString("id")))result.put("failed",completed.optString("status").equals("paused")&&!completed.optString("message").contains("时段已结束"));}
        return result;
    }
    public void reserveControl(){if(runner.isRunning()||!controlBusy.compareAndSet(false,true))throw new IllegalStateException("该平台正在运行");}
    public Map<String,Object> checkReservedSession() { return checkReservedSession(false); }
    public Map<String,Object> checkReservedSession(boolean visible) {
        if(!controlBusy.get())throw new IllegalStateException("未取得平台控制名额");
        try {
        if(!live)throw new IllegalStateException("开发浏览器未开启");
        if(protectedAccount())throw new IllegalStateException("生产账号保护，不能在开发版登录旧版账号");
        if(visible){browser.setBackgroundMode(false);browser.setHeadlessMode(false);}else prepareAutomaticBrowser();manualBrowser=visible;
        browser.ensureReady();if(visible){browser.callOnPlaywright(()->{browser.getBossPage().bringToFront();return null;});return Map.of("loggedIn",session.getOrDefault("loggedIn",false),"message","已显示专用浏览器，请手动处理；未刷新页面或检查登录");}session=browser.callOnPlaywright(()->id.equals("shixiseng")?runner.checkShixisengSession():id.equals("boss")?browser.checkAndSaveBossSession():nativeSites.checkSession(browser.getBossPage()));return session;}
        finally{controlBusy.set(false);}
    }
    public Object reservedDiagnostic(String url) {
        if(!controlBusy.get())throw new IllegalStateException("未取得平台控制名额");
        try{if(!browser.isInitialized())return Map.of("message","浏览器尚未打开");
            if(id.equals("shixiseng")&&(url==null||url.isBlank()))return runner.shixisengDiagnostic();
            return browser.callOnPlaywright(()->url==null||url.isBlank()?nativeSites.diagnostic(browser.getBossPage()):nativeSites.inspectDetail(browser.getBossPage(),url));}
        finally{controlBusy.set(false);}
    }
    public synchronized void manualSend(String job,boolean acceptance) {
        if(running())throw new IllegalStateException("该平台正在运行");
        if(manualBrowser)throw new IllegalStateException("请先返回无界面运行");
        prepareAutomaticBrowser();
        if(!sending||protectedAccount())throw new IllegalStateException("开发副本发送锁定");
        if(!config().optBoolean("resumeReady"))throw new IllegalStateException("该平台简历尚未就绪");
        if(!acceptance&&!effective().getString("mode").equals("auto"))throw new IllegalStateException("该平台尚未通过真实投递验收");
        JSONObject entry=store.get(job);if(entry==null||store.blocksRetry(entry))throw new IllegalStateException("岗位不存在或已尝试发送");
        JSONObject current=effective();store.saveSettings(current);
        if(entry.getJSONObject("result").optBoolean("pendingModel"))throw new IllegalStateException("模型判断未完成，不能自动确认");
        if(entry.getJSONObject("result").optString("decision").equals("review"))store.confirmReview(job,"按用户授权确认此岗位并进行独立新版投递验收","scheduled-user-authorization");
        store.approve(job);String day=today();if(!repository.claim(id,"default",job,day))throw new IllegalStateException("该岗位当日已有投递或尚未确认的发送");
        manualClaims.put(job,day);try{runner.deliver(job);}catch(Exception e){finishManualClaims();throw e;}
    }
    public Object verifyReservedReceipt(String job,String url) {
        if(!controlBusy.get())throw new IllegalStateException("未取得平台控制名额");
        try{JSONObject entry=store.get(job);if(entry==null)throw new IllegalArgumentException("岗位不存在");prepareAutomaticBrowser();browser.ensureReady();
            Object proof=browser.callOnPlaywright(()->nativeSites.verifyExistingReceipt(job,entry,browser.getBossPage(),url));
            repository.resolveConfirmed(id,job);return proof;
        }finally{controlBusy.set(false);}
    }
    public synchronized void continueNative(String job) {
        if(!id.equals("zhilian")||running()||!sending||!config().optBoolean("resumeReady"))throw new IllegalStateException("不能继续该平台的未完成投递");
        JSONObject state=store.scheduleState().getJSONObject("platforms").getJSONObject(id);
        if(today().equals(state.optString("quotaDate")))throw new IllegalStateException("当日上限仍有效，未继续发送");
        String blocked=blocked(today());if(!blocked.isBlank()&&!blocked.contains("未确认投递成功"))throw new IllegalStateException(blocked);
        store.saveSettings(effective());manualClaims.put(job,today());try{runner.continueNativeDelivery(job);}catch(RuntimeException e){manualClaims.remove(job);throw e;}
    }
    public synchronized void finishManualClaims(){
        if(running())return;
        for(var claim:manualClaims.entrySet()){
            JSONObject entry=store.get(claim.getKey());String status=entry==null?"native_attempted":entry.optString("status","screened");
            repository.finishClaim(id,"default",claim.getKey(),claim.getValue(),status);
            if(id.equals("boss")&&status.equals("complete")&&entry.optBoolean("textConfirmed")&&entry.optBoolean("imageConfirmed")) {
                JSONObject proof=new JSONObject().put("liveSubmissionVerified",true).put("recordId",claim.getKey()).put("at",Instant.now().toString());
                store.updateSchedule(s->s.put("bossAcceptance",proof));
            }
            if(!status.equals("complete")) {
                String reason=String.valueOf(runner.status().get("progress"));
                store.holdPlatform(id,today(),reason);
                repository.event(id,"manual|"+claim.getKey(),"manual_delivery_failed",reason+"；该平台已暂停，请核对投递记录后处理");
            }
        }manualClaims.clear();
    }
    public Map<String,Object> status() {
        JSONObject config=config();Map<String,Object> result=new LinkedHashMap<>();result.put("browserMode",manualBrowser?"visible":backgroundBrowser()?"background":"headless");result.put("browserInitialized",browser.isInitialized());result.put("id",id);result.put("label",Platforms.label(id));result.put("running",running());result.put("progress",runner.status().get("progress"));result.put("config",config.toMap());result.put("session",session);result.put("protectedAccount",protectedAccount());result.put("round",store.roundSummary().toMap());result.put("blockedReason",blocked(today()));result.put("effectiveMode",effective().optString("mode"));result.put("liveVerified",liveVerified());return result;
    }
    public void close(){if(browser.isInitialized())browser.callOnPlaywright(()->{browser.destroy();return null;});}
}
