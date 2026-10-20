package com.getjobs.worker.boss;

import com.getjobs.application.service.AiService;
import com.getjobs.application.service.BossResumeImageService;
import com.getjobs.application.service.ConfigService;
import com.getjobs.worker.manager.PlaywrightManager;
import com.microsoft.playwright.*;
import org.json.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class InternshipRunner {
    private final InternshipStore store;
    private final AiService ai;
    private final PlaywrightManager manager;
    private final ObjectProvider<Boss> bossProvider;
    private final BossResumeImageService images;
    private final ConfigService configService;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile boolean stopped;
    private volatile String progress = "就绪，尚未开始本轮";
    java.time.Clock clock=java.time.Clock.systemUTC();
    private volatile java.time.Instant scheduledDeadline;
    private volatile boolean deliveryInFlight;
    private int collectionReserveSeconds=300,sendReserveSeconds=60;
    private InternshipNativeSites nativeSites;
    public interface DeliveryGate {boolean claim(String id,JSONObject entry);void finish(String id,JSONObject entry);}
    private DeliveryGate deliveryGate;
    public InternshipRunner isolated(InternshipStore isolatedStore,PlaywrightManager browser,InternshipModel sharedModel,boolean live,boolean sending,boolean modeling,InternshipNativeSites sites,DeliveryGate gate) {
        InternshipRunner fork=new InternshipRunner(isolatedStore,ai,browser,bossProvider,images,configService);
        fork.model=sharedModel;fork.shixiseng=new InternshipShixiseng(isolatedStore);fork.nativeSites=sites;fork.deliveryGate=gate;
        fork.liveEnabled=live;fork.sendingEnabled=sending;fork.modelEnabled=modeling;
        fork.collectionReserveSeconds=600;fork.sendReserveSeconds=120;return fork;
    }
    public void capabilities(boolean live,boolean sending,boolean modeling){if(running.get())throw new IllegalStateException("平台仍在运行");liveEnabled=live;sendingEnabled=sending;modelEnabled=modeling;}
    private boolean reserveDelivery(String id,JSONObject entry){return deliveryGate==null||deliveryGate.claim(id,entry);}
    private void finishDelivery(String id){if(deliveryGate!=null)deliveryGate.finish(id,store.get(id));}
    private volatile String scheduledPlatform="boss",phase="idle";
    boolean collectionEnded() {
        return shouldStop() || scheduledDeadline!=null&&!clock.instant().isBefore(scheduledDeadline.minusSeconds(collectionReserveSeconds));
    }
    boolean sendWindowEnded() {
        return shouldStop() || scheduledDeadline!=null&&!clock.instant().isBefore(scheduledDeadline.minusSeconds(sendReserveSeconds));
    }
    private volatile String scheduledSlot="",scheduledRoundId="";
    private volatile boolean scheduledAutoReview,scheduledDeadlineReached;
    @Autowired private InternshipModel model;
    @Autowired private InternshipShixiseng shixiseng;
    @Autowired(required=false) private InternshipFailureNotifier failureNotifier;
    @Value("${internship.live-enabled:false}") private boolean liveEnabled;
    @Value("${internship.sending-enabled:false}") private boolean sendingEnabled;
    @Value("${internship.model-enabled:false}") private boolean modelEnabled;

    public InternshipRunner(InternshipStore store, AiService ai, PlaywrightManager manager,
                            ObjectProvider<Boss> bossProvider, BossResumeImageService images, ConfigService configService) {
        this.store = store; this.ai = ai; this.manager = manager; this.bossProvider = bossProvider; this.images = images;
        this.configService = configService;
    }
    public Map<String,Object> status() {
        Map<String,Object> state=new LinkedHashMap<>();
        state.put("phase",phase);state.put("scheduledPlatform",scheduledPlatform);state.put("scheduledDeadline",scheduledDeadline==null?"":scheduledDeadline.toString());
        state.put("running",running.get());state.put("stopped",stopped);state.put("progress",progress);
        state.put("liveEnabled",liveEnabled);state.put("sendingEnabled",sendingEnabled);state.put("modelEnabled",modelEnabled);
        state.put("bossLoggedIn",manager!=null && manager.isLoggedIn("boss"));
        state.put("bossAccount",manager==null?"":manager.getBossAccountLabel());
        state.put("bossSessionSavedAt",manager==null?"":manager.getBossSessionSavedAt());
        state.put("model",model==null?Map.of("provider","none","ready",false,"message","未连接"):model.status());
        state.put("resumeImage",images==null?Map.of("exists",false):images.getMetadata());state.put("version","1.7.1");
        Map<String,Object> sxs=new LinkedHashMap<>(shixiseng==null?Map.of("loggedIn",false):shixiseng.status());
        sxs.put("resumeConfirmed",store.settings().optBoolean("shixisengResumeConfirmed"));state.put("shixiseng",sxs);
        state.put("round",store.roundSummary().toMap());state.put("schedule",store.scheduleState().toMap());return state;
    }
    public Map<String,Object> checkModel() {
        if(!modelEnabled) throw new IllegalStateException("模型功能尚未开启");
        if(!running.compareAndSet(false,true)) throw new IllegalStateException("请等本轮结束后测试模型");
        stopped=false;progress="正在测试模型连接";
        try {var result=model.check(this::shouldStop);progress="模型测试完成";return result;}
        finally {running.set(false);}
    }
    public boolean isRunning() { return running.get(); }
    public Object shixisengDiagnostic() {
        if(isRunning())throw new IllegalStateException("请先等待本轮结束");
        return manager.callOnPlaywright(()->shixiseng.diagnostic(manager.getBossPage()));
    }
    public Map<String,Object> checkShixisengSession() {
        if(!liveEnabled)throw new IllegalStateException("真实岗位模式尚未开启");
        if(!running.compareAndSet(false,true))throw new IllegalStateException("请先暂停并等待本轮结束");
        try {
            manager.ensureReady();manager.pauseBossMonitoring();
            var result=manager.callOnPlaywright(()->shixiseng.checkSession(manager.getBossPage()));progress=result.get("message").toString();return result;
        } finally {manager.resumeBossMonitoring();running.set(false);}
    }
    public Map<String,Object> checkBossSession() {
        if(!liveEnabled)throw new IllegalStateException("真实岗位模式尚未开启");
        if(!running.compareAndSet(false,true))throw new IllegalStateException("请先暂停本轮");
        progress="正在核对专用Chrome登录态";
        try {
            manager.ensureReady();manager.pauseBossMonitoring();
            Map<String,Object> result=manager.callOnPlaywright(()->manager.checkAndSaveBossSession());
            progress=result.get("message").toString();return result;
        } finally {manager.resumeBossMonitoring();running.set(false);}
    }
    public Map<String,Object> openJob(String id) {
        JSONObject entry=store.get(id);
        if(!liveEnabled || entry==null)throw new IllegalArgumentException("岗位不存在或真实岗位模式未开启");
        if(!running.compareAndSet(false,true))throw new IllegalStateException("请先暂停本轮，再查看原岗位");
        stopped=false;progress="正在专用Chrome打开原岗位";
        try {
            manager.ensureReady();manager.pauseBossMonitoring();
            return manager.callOnPlaywright(()->{
                JSONObject job=entry.getJSONObject("job");Page page=manager.getBossPage().context().newPage();
                if(job.optString("platform").equals("shixiseng")) {page.close();return shixiseng.openJob(manager.getBossPage(),job);}
                page.setDefaultTimeout(10000);page.setDefaultNavigationTimeout(25000);
                page.navigate(navigationUrl(job));page.bringToFront();
                if(risk(page))return Map.of("opened",true,"verified",false,"message","已打开专用Chrome，请完成BOSS登录或安全验证");
                boolean verified=verifiedDetail(page,job);
                if(!verified) {
                    JSONObject fresh=findCurrentJob(manager.getBossPage(),job);
                    page.navigate(navigationUrl(fresh));page.bringToFront();verified=!risk(page)&&verifiedDetail(page,fresh);
                }
                progress=verified?"已在专用Chrome打开原岗位，未发送消息":"已打开原岗位，请核对页面内容";
                return Map.of("opened",true,"verified",verified,"message",progress);
            });
        } finally {manager.resumeBossMonitoring();running.set(false);}
    }
    public void prepareShutdown() {
        if(running.get())throw new IllegalStateException("请先暂停并等本轮结束");
        if(manager.isInitialized())manager.callOnPlaywright(()->{
            if(manager.isLoggedIn("boss"))manager.saveCookiesToDb("boss","workbench shutdown");
            manager.destroy();return 0;
        });
    }
    public void reassess(String id) {
        JSONObject entry=store.get(id),c=store.settings();
        if(!modelEnabled || entry==null || !entry.optString("status").equals("screened"))
            throw new IllegalStateException("仅能重新判断未投递的岗位，请检查模型是否开启");
        if(!running.compareAndSet(false,true)) throw new IllegalStateException("任务正在运行");
        stopped=false;progress="模型正在重新判断："+entry.getJSONObject("job").optString("title");
        CompletableFuture.runAsync(()->{
            try {
                entry.put("result",analyze(c,entry.getJSONObject("job"))).put("settingsHash",InternshipPolicy.fingerprint(c))
                        .put("approved",false).put("assessmentSource",entry.getJSONObject("result").optString("engine","model"));
                store.save(id,entry);progress="岗位判断已更新，请查看理由";
            } catch(Exception e) {progress="判断失败，请检查模型连接";}
            finally {running.set(false);}
        });
    }
    public Object pageSnapshot() {
        if(running.get()) throw new IllegalStateException("先等本轮停止");
        return manager.callOnPlaywright(() -> manager.getBossPage().context().pages().getLast().evaluate("""
            () => ({url:location.href,title:document.title,
              text:document.body.innerText.slice(0,5000),
              cardClasses:Array.from(document.querySelectorAll('[class*=job-card]')).slice(0,8).map(e=>e.className),
              links:Array.from(document.querySelectorAll('a[href*="job_detail"]')).slice(0,6).map(e=>({href:e.getAttribute('href'),text:e.innerText,container:e.parentElement.className})),
              detailClasses:Array.from(document.querySelectorAll('[class*=job-detail],[class*=job-sec]')).slice(0,8).map(e=>e.className),
              chatElements:Array.from(document.querySelectorAll('[class*=chat],[class*=boss],[class*=message]')).slice(0,70).map(e=>({tag:e.tagName,cls:e.className,text:e.innerText?.slice(0,1500)}))})
            """));
    }
    public Object inspectConversation(String id) {
        JSONObject entry=store.get(id);
        if(entry==null || !InternshipStore.attempted(entry))throw new IllegalStateException("仅能核对已尝试沟通的岗位");
        if(!running.compareAndSet(false,true))throw new IllegalStateException("先等本轮停止");
        stopped=false;
        try {
            manager.ensureReady();manager.pauseBossMonitoring();
            return manager.callOnPlaywright(()->{
                JSONObject job=entry.getJSONObject("job");
                Page detail=manager.getBossPage().context().newPage();detail.navigate(navigationUrl(job));
                if(risk(detail) || !verifiedDetail(detail,job))throw new IllegalStateException("岗位详情或身份核对失败，未发送消息");
                Locator button=detail.locator("a.btn-startchat,a.op-btn-chat").first();button.waitFor();
                button.click();detail.locator("#chat-input[contenteditable='true'],textarea.input-area").first().waitFor();
                detail.waitForTimeout(1000);
                Object snapshot=detail.evaluate("""
                    () => ({url:location.href.split('?')[0],text:document.body.innerText.slice(-12000),
                      ownMessages:Array.from(document.querySelectorAll('.chat-conversation li.message-item.item-myself')).map(e=>({text:e.innerText,classes:e.className,images:e.querySelectorAll('.image-container img,.image-box img,img.message-image').length,imageDetails:Array.from(e.querySelectorAll('img.message-image')).map(i=>({width:i.naturalWidth,height:i.naturalHeight,complete:i.complete}))})),
                      elements:Array.from(document.querySelectorAll('.chat-conversation [class*=chat],.chat-conversation [class*=boss],.chat-conversation [class*=message]')).slice(0,60).map(e=>({tag:e.tagName,cls:e.className,text:e.innerText?.slice(0,2500)}))})
                    """);
                JSONObject inspection=new JSONObject((Map<?,?>)snapshot);
                inspection.put("headerText",conversationIdentity(detail));
                java.nio.file.Path screenshot=java.nio.file.Path.of("data","internship","diagnostics","conversation-"+id+".png").toAbsolutePath();
                try {
                    java.nio.file.Files.createDirectories(screenshot.getParent());
                    detail.locator(".chat-conversation").first().screenshot(new Locator.ScreenshotOptions().setPath(screenshot));
                    inspection.put("screenshotPath",screenshot.toString());
                } catch(Exception ignored) { /* Text inspection remains usable if capture fails. */ }
                return inspection.toMap();
            });
        } finally {manager.resumeBossMonitoring();running.set(false);}
    }
    public JSONObject preview(String id) {
        if(!liveEnabled) throw new IllegalStateException("真实岗位读取尚未开启");
        JSONObject entry=store.get(id);
        if(entry==null) throw new IllegalArgumentException("岗位不存在");
        if(!running.compareAndSet(false,true)) throw new IllegalStateException("请等本轮结束");
        try {
            manager.ensureReady();manager.pauseBossMonitoring();
            return manager.callOnPlaywright(()->{
                Page page=manager.getBossPage();
                JSONObject fresh=findCurrentJob(page,entry.getJSONObject("job"));
                Page detail=page.context().newPage();
                try {
                    detail.navigate(navigationUrl(fresh));
                    detail.locator("a.btn-startchat,a.op-btn-chat").first().waitFor();
                    return new JSONObject().put("job",fresh).put("same",sameDescription(entry.getJSONObject("job"),fresh))
                            .put("detail",detail.evaluate("""
                                () => ({url:location.href.split('?')[0],text:document.body.innerText.slice(0,10000),
                                controls:Array.from(document.querySelectorAll('h1,.job-name,.info-primary,.job-primary,.boss-info,.boss-name,.sider-company,.job-banner')).slice(0,20).map(e=>({tag:e.tagName,cls:e.className,text:e.innerText.slice(0,1800)}))})
                                """));
                } finally {detail.close();}
            });
        } finally {manager.resumeBossMonitoring();running.set(false);}
    }
    public boolean isScheduledRun() {return !scheduledSlot.isBlank();}
    public boolean shouldStop() {
        if(!stopped&&scheduledDeadline!=null&&(!deliveryInFlight||clock.instant().atZone(InternshipSchedule.ZONE).getHour()>=17)&&!clock.instant().isBefore(scheduledDeadline)) {
            stopped=true;scheduledDeadlineReached=true;progress="本轮时段已结束，停止新增投递";
        }
        return stopped;
    }
    public void checkScheduledDeadline() {shouldStop();}
    public void stop() {
        stopped=true;progress="暂停请求已收到，将在下一次发送前停止";
        if(isScheduledRun())store.holdPlatform(scheduledPlatform,clock.instant().atZone(InternshipSchedule.ZONE).toLocalDate().toString(),"用户手动暂停，今天不再自动启动新轮次");
    }
    public void deliver(String id) {
        JSONObject c=store.settings(), entry=store.get(id);
        if(!liveEnabled || !sendingEnabled) throw new IllegalStateException("真实投递尚未开启");
        if(entry==null || entry.optBoolean("excludedFromPool") || !InternshipPolicy.formalReason(entry.getJSONObject("job")).isBlank()
                || !entry.optBoolean("approved") || !InternshipPolicy.deliveryEligible(c,entry)
                || !entry.optString("settingsHash").equals(InternshipPolicy.fingerprint(c)))
            throw new IllegalStateException("先审核并批准当前资料下的匹配岗位");
        if(store.blocksRetry(entry))
            throw new IllegalStateException("该岗位已尝试投递，请先核对会话");
        String nativePlatform=entry.getJSONObject("job").optString("platform","boss");
        if(!nativePlatform.equals("boss")&&!nativePlatform.equals("shixiseng")) {
            if(nativeSites==null)throw new IllegalStateException("平台适配未启用");
            if(!running.compareAndSet(false,true))throw new IllegalStateException("平台正在运行");
            stopped=false;progress="正在核对站内简历投递";
            CompletableFuture.runAsync(()->{
                try {manager.ensureReady();manager.callOnPlaywright(()->nativeSites.deliver(c,id,entry,manager.getBossPage(),this::shouldStop));progress="站内简历投递已确认";}
                catch(Exception e){progress="已停止："+e.getMessage();}finally{running.set(false);}
            });return;
        }
        if(entry.getJSONObject("job").optString("platform").equals("shixiseng")) {
            if(!c.optBoolean("shixisengResumeConfirmed"))throw new IllegalStateException("请先核对并确认实习僧站内简历");
            if(!running.compareAndSet(false,true))throw new IllegalStateException("任务正在运行");
            stopped=false;progress="正在核对实习僧岗位和站内简历投递";
            CompletableFuture.runAsync(()->{
                try {manager.ensureReady();manager.pauseBossMonitoring();
                    boolean sent=manager.callOnPlaywright(()->shixiseng.deliver(c,id,entry,manager.getBossPage(),this::shouldStop));
                    progress=sent?"实习僧站内简历已投递并确认":"实习僧未新增提交，请查看岗位记录";
                } catch(Exception e) {progress="实习僧投递已停止："+e.getMessage();
                    if(failureNotifier!=null&&!progress.contains("当日投递上限"))failureNotifier.report("round_failed","实习僧单条投递",progress);}
                finally {manager.resumeBossMonitoring();running.set(false);}
            });return;
        }
        if(!c.optBoolean("confirmed") || c.optString("greeting").isBlank() || images.getCurrentImagePath().isEmpty())
            throw new IllegalStateException("请确认资料、填写招呼语并上传固定简历图片");
        if(!running.compareAndSet(false,true)) throw new IllegalStateException("任务正在运行");
        stopped=false;progress="正在核对所选岗位";
        CompletableFuture.runAsync(()->{
            try {
                manager.ensureReady();
                if(!manager.isLoggedIn("boss")) throw new IllegalStateException("请手动登录BOSS");
                manager.pauseBossMonitoring();
                manager.callOnPlaywright(()->{
                    if(!Boolean.TRUE.equals(manager.checkAndSaveBossSession().get("loggedIn")))
                        throw new IllegalStateException("专用Chrome登录已失效，请登录后重试；未发送消息");
                    Page detail=manager.getBossPage().context().newPage();
                    try {
                        JSONObject expected=entry.getJSONObject("job");
                        detail.setDefaultTimeout(10000);detail.setDefaultNavigationTimeout(25000);
                        detail.navigate(navigationUrl(expected));
                        if(risk(detail))return 0;
                        if(!verifiedDetail(detail,expected)) {
                            JSONObject fresh=findCurrentJob(manager.getBossPage(),expected);
                            if(!sameDescription(expected,fresh)) {
                                entry.put("job",fresh).put("jdHash",InternshipPolicy.hash(fresh.toString()))
                                        .put("result",analyze(c,fresh)).put("approved",false);
                                store.save(id,entry);progress="岗位信息有变化，已重新判断，请核对后再次批准";return 0;
                            }
                            detail.navigate(navigationUrl(fresh));
                        }
                        if(risk(detail) || !verifiedDetail(detail,expected))throw new IllegalStateException("岗位详情不完整或条件已变化，未发送");
                        boolean complete=InternshipDelivery.send(store,id,entry,c.getString("greeting"),this::shouldStop,port(detail,expected));
                        progress=complete?"此岗位投递完成：文字和简历图片均已确认":"投递未完成："+entry.optString("lastDeliveryIssue","请核对会话后处理");
                        return 0;
                    } finally { closeDetailIfSafe(detail,id); }
                });
            } catch(Exception e) {progress="已停止："+e.getMessage();}
            finally {manager.resumeBossMonitoring();running.set(false);}
        });
    }
    public void continueNativeDelivery(String id) {
        JSONObject c=store.settings(),entry=store.get(id);
        if(!liveEnabled||!sendingEnabled||nativeSites==null||entry==null||!entry.optString("status").equals("native_attempted")
                ||!entry.getJSONObject("job").optString("platform").equals("zhilian")||!entry.optBoolean("approved")
                ||!entry.optString("settingsHash").equals(InternshipPolicy.fingerprint(c))||!InternshipPolicy.deliveryEligible(c,entry))throw new IllegalStateException("仅继续当前资料下已批准、未完成的智联投递");
        if(!running.compareAndSet(false,true))throw new IllegalStateException("平台正在运行");stopped=false;progress="正在继续原智联会话的简历步骤，不重新打招呼";
        CompletableFuture.runAsync(()->{try{manager.ensureReady();boolean confirmed=manager.callOnPlaywright(()->nativeSites.continueExistingDelivery(id,entry,manager.getBossPage(),this::shouldStop));progress=confirmed?"原会话附件简历已确认":"未新增发送，请核对原会话";}
            catch(Exception e){progress="已停止："+e.getMessage();}finally{running.set(false);}});
    }
    static boolean sameDescription(JSONObject expected,JSONObject fresh) {
        for(String field:List.of("title","company","recruiter","salary","jd")) {
            String a=expected.optString(field).replaceAll("\\s+",""),b=fresh.optString(field).replaceAll("\\s+","");
            if(a.isBlank() || !a.equals(b))return false;
        }
        if(expected.optString("city").isBlank() || !fresh.optString("city").contains(expected.getString("city")))return false;
        return true;
    }
    public void start() {
        start(store.settings(),null,"",false);
    }
    public void checkShixisengRead(String source) {
        JSONObject settings=store.settings().put("platform","shixiseng").put("mode","scan").put("maxScan",3).put("refreshRounds",1);
        if(!source.isBlank()) {
            if(!java.util.Set.of("recommend","search").contains(source))throw new IllegalArgumentException("检查来源无效");
            settings.put("source",source);
        }
        start(settings,null,"",false);
    }
    public void startScheduled(String source,String slot,java.time.Instant deadline) {startScheduled("boss",source,slot,deadline);}
    public void startScheduled(String platform,String source,String slot,java.time.Instant deadline) {
        if(!java.util.Set.of("boss","shixiseng").contains(platform))throw new IllegalArgumentException("定时平台无效");
        if(!java.util.Set.of("recommend","search").contains(source)||!clock.instant().isBefore(deadline))
            throw new IllegalStateException("定时来源无效或已超过截止时间");
        JSONObject settings=store.settings().put("source",source).put("mode","auto").put("platform",platform);
        start(settings,deadline,slot,true);
    }
    public void startConfiguredRound(JSONObject settings,String slot,java.time.Instant deadline) {
        if(!clock.instant().isBefore(deadline))throw new IllegalStateException("本时段已过期");
        long budget=java.time.Duration.between(clock.instant(),deadline).toSeconds();
        collectionReserveSeconds=(int)Math.max(30,Math.min(280,budget/3));
        sendReserveSeconds=(int)Math.max(10,Math.min(45,budget/12));
        start(new JSONObject(settings.toString()),deadline,slot,settings.optBoolean("autoConfirmReview",false));
    }
    private void start(JSONObject settings,java.time.Instant deadline,String slot,boolean autoReview) {
        if (!liveEnabled) throw new IllegalStateException("真实岗位读取尚未开启，请使用桌面启动入口");
        if (!sendingEnabled && !settings.getString("mode").equals("scan"))
            throw new IllegalStateException("当前只读验收，请使用只筛选模式；发送仍锁定");
        if(settings.optString("platform","boss").equals("shixiseng")&&!settings.getString("mode").equals("scan")&&!settings.optBoolean("shixisengResumeConfirmed"))
            throw new IllegalStateException("请先登录实习僧、核对站内简历，并确认使用站内简历投递");
        if (!running.compareAndSet(false, true)) throw new IllegalStateException("任务正在运行");
        scheduledPlatform=settings.optString("platform","boss");phase="collect";deliveryInFlight=false;
        scheduledDeadline=deadline;scheduledSlot=slot;scheduledRoundId="";scheduledAutoReview=autoReview;scheduledDeadlineReached=false;
        stopped = false;progress="正在启动本轮，准备读取岗位列表";
        CompletableFuture.runAsync(() -> {
            try {
                if (!settings.getBoolean("confirmed")) throw new IllegalStateException("先确认简历事实和偏好");
                if (settings.optString("platform","boss").equals("boss")&&!settings.getString("mode").equals("scan") && (settings.getString("greeting").isBlank()
                        || images.getCurrentImagePath().isEmpty())) throw new IllegalStateException("投递前填写招呼语并上传固定图片简历");
                manager.ensureReady();
                if (settings.optString("platform","boss").equals("boss")&&!manager.isLoggedIn("boss")) throw new IllegalStateException("请手动登录账号");
                manager.pauseBossMonitoring();
                manager.callOnPlaywright(() -> {if(settings.optString("platform","boss").equals("shixiseng"))scanShixiseng(settings);else if(settings.optString("platform","boss").equals("boss"))scan(settings);else scanNativeSite(settings); return 0; });
                if(!shouldStop()) progress = "本轮处理完成";
            } catch (Exception e) { stopped=true;scheduledDeadlineReached=false;progress = "已停止：" + e.getMessage(); }
            finally {
                store.endRound(stopped?"paused":"finished");
                if(!slot.isBlank())store.finishScheduled(slot,progress,stopped,scheduledDeadlineReached,
                        scheduledRoundId.isBlank()?new JSONObject():store.roundSummary());
                else if(stopped&&settings.optString("platform","boss").equals("shixiseng")&&failureNotifier!=null
                        &&!progress.contains("暂停请求已收到")&&!progress.contains("当日投递上限"))
                    failureNotifier.report("round_failed","实习僧 · "+store.roundSummary().optString("id"),progress);
                scheduledSlot="";scheduledDeadline=null;scheduledAutoReview=false;phase="idle";deliveryInFlight=false;
                manager.resumeBossMonitoring();running.set(false);
            }
        });
    }

    public JSONObject analyze(JSONObject settings, JSONObject job) {
        JSONObject preliminary = InternshipPolicy.evaluate(settings, job, "");
        if (!preliminary.getString("reason").equals("模型请求失败或JSON不符合约定")) return preliminary.put("engine","local-rule");
        if(!modelEnabled) return InternshipPolicy.review("已采集完整岗位描述，等待本聊天判断或配置模型服务").put("pendingModel",true);
        String prompt=InternshipPolicy.prompt(settings,job);
        try {
            for(int attempt=1;attempt<=2;attempt++) {
                String answer=model==null?ai.sendRequest(prompt):model.classify(prompt,()->phase.equals("collect")?collectionEnded():shouldStop());
                JSONObject result=InternshipPolicy.evaluate(settings,job,answer).put("engine",model==null?"api":model.status().get("provider")).put("validationAttempts",attempt);
                if(!result.has("validationFailure")||attempt==2||shouldStop()||(phase.equals("collect")&&collectionEnded()))return result;
                prompt=InternshipPolicy.prompt(settings,job)+"\n上一次答复未通过引用校验："+result.getJSONObject("validationFailure")
                        +"\n请重新完整判断并返回约定JSON。引用必须从jd或resumeText中复制连续原文，不能改写、补充经历、合并不相邻片段或用省略号代替。"
                        +"不能找到简历依据时将resumeEvidence留空并如实调整decision、分数和unknowns。上次答复仅供纠错，不是事实依据：\n"+answer;
            }
            throw new IllegalStateException("判断未完成");
        } catch (Exception e) { return InternshipPolicy.review("模型调用失败，未执行沟通").put("pendingModel",true); }
    }

    void scan(JSONObject c) {
        store.archiveFormalRecords();
        String roundId=store.beginRound(c.getInt("maxScan"));
        if(isScheduledRun())scheduledRoundId=roundId;
        Page list = manager.getBossPage();
        list.setDefaultTimeout(8000);
        list.setDefaultNavigationTimeout(20000);
        List<String> urls = new ArrayList<>();
        if (c.getString("source").equals("recommend")) urls.add("https://www.zhipin.com/web/geek/jobs");
        else {
            if (c.getJSONArray("keywords").isEmpty()) throw new IllegalStateException("搜索模式需要关键词");
            // Existing Boss settings supply native filters; InternshipPolicy checks cities against the actual JD.
            BossConfig nativeConfig = configService.getBossConfig();
            List<String> cityCodes = nativeConfig.getCityCode();
            if (cityCodes == null || cityCodes.isEmpty()) cityCodes = List.of("100010000");
            for (String cityCode : cityCodes) {
                String base = Boss.buildSearchUrl(nativeConfig, cityCode);
                for (Object word : c.getJSONArray("keywords")) urls.add(base + (base.contains("?") ? "&" : "?") + "query=" + URLEncoder.encode(word.toString(), StandardCharsets.UTF_8));
            }
        }
        Set<String> seen = new HashSet<>();
        int scanned = 0;
        for (String url : urls) {
            if(collectionEnded()||scanned>=c.getInt("maxScan"))break;
            for (int round = 0; round < c.getInt("refreshRounds") && !collectionEnded(); round++) {
                if(scanned>=c.getInt("maxScan"))break;
                list.navigate(url);
                if (risk(list)) return;
                list.locator(".job-card-wrap, .job-card-wrapper").first().waitFor();
                internshipFilter(list);
                int stable = 0;
                while (!collectionEnded() && scanned < c.getInt("maxScan") && stable < 3) {
                    Locator links = list.locator(".job-card-wrap a[href*='/job_detail/'], .job-card-wrapper a[href*='/job_detail/']");
                    List<String> details = new ArrayList<>();
                    boolean sawNew=false;
                    for (int i=0; i<links.count(); i++) {
                        String href = links.nth(i).getAttribute("href");
                        if (href == null) continue;
                        String target = href.startsWith("/") ? "https://www.zhipin.com" + href : href;
                        if (!target.matches("https://www\\.zhipin\\.com/job_detail/[^/?]+\\.html(?:\\?.*)?")) continue;
                        target = target.split("\\?")[0];
                        if (seen.add(target)) {
                            sawNew=true;
                            JSONObject summary=cardSummary(links.nth(i),target);
                            String reason=InternshipPolicy.formalReason(summary);
                            if(!reason.isBlank()) {store.exclude(InternshipPolicy.hash(target),summary,reason,roundId);continue;}
                            details.add(target);
                        }
                    }
                    stable = sawNew ? 0 : stable+1;
                    for (String target : details) {
                        if (collectionEnded() || scanned >= c.getInt("maxScan")) break;
                        String id = InternshipPolicy.hash(target);
                        JSONObject old = store.get(id);
                        boolean imageOnly=old!=null && old.optString("status").equals("ready_image");
                        if (store.blocksRetry(old)) continue;
                        Page detail = list;
                        try {
                            detail.setDefaultTimeout(8000); detail.setDefaultNavigationTimeout(20000);
                            JSONObject job=readInlineJob(list,target);
                            if (risk(detail)) return;
                            String excluded=InternshipPolicy.formalReason(job);
                            if(!excluded.isBlank()) {store.exclude(id,job,excluded,roundId);continue;}
                            scanned++;
                            JSONObject result;
                            String configHash = InternshipPolicy.fingerprint(c), jdHash = InternshipPolicy.hash(job.toString());
                            boolean same = old != null && !store.completedBeforeToday(old) && old.optString("settingsHash").equals(configHash) && old.optString("jdHash").equals(jdHash);
                            if (imageOnly && !same) continue;
                            progress="正在判断第 "+scanned+" 个实习候选："+job.optString("title");
                            result = same && !old.getJSONObject("result").optBoolean("pendingModel") && !(old.getJSONObject("result").optString("engine").equals("codex")&&old.getJSONObject("result").optJSONArray("jdEvidence")!=null&&old.getJSONObject("result").getJSONArray("jdEvidence").isEmpty()) ? old.getJSONObject("result") : analyze(c, job);
                            if(collectionEnded())break;
                            JSONObject entry = new JSONObject().put("job", job).put("result", result).put("status", imageOnly ? "ready_image" : "screened")
                                    .put("approved", same && old.optBoolean("approved")).put("settingsHash", configHash).put("jdHash", jdHash).put("roundId",roundId)
                                    .put("assessmentSource",same?old.optString("assessmentSource","previous"):result.optString("engine","model"));
                            store.save(id, entry);
                            if(result.optBoolean("pendingModel") && modelEnabled) {
                                stopped=true;progress="模型未完成有效判断，已暂停；未执行沟通："+result.optString("reason");return;
                            }
                            progress = "正在采集与评分，尚未发送：已读取 " + scanned + " 个岗位，" + job.optString("title") + " / " + result.getString("decision");
                        } finally { if(detail!=list) closeDetailIfSafe(detail,id); }
                        pause(c.getInt("intervalSeconds"));
                    }
                    scrollList(list);
                    list.waitForTimeout(1000);
                    if (risk(list)) return;
                }
            }
        }
        if(!shouldStop())rankAndDeliver(c,roundId,list);
    }

    void scanShixiseng(JSONObject c) {
        String roundId=store.beginRound(c.getInt("maxScan"));
        if(isScheduledRun())scheduledRoundId=roundId;
        shixiseng.collect(c,manager.getBossPage(),this::collectionEnded,job->{
            if(collectionEnded())return;String id=InternshipPolicy.hash(job.getString("url"));
            String formal=InternshipPolicy.formalReason(job);if(!formal.isBlank()){store.exclude(id,job,formal,roundId);return;}
            JSONObject result=analyze(c,job);if(collectionEnded())return;
            store.save(id,new JSONObject().put("job",job).put("result",result).put("status","screened").put("approved",false)
                    .put("settingsHash",InternshipPolicy.fingerprint(c)).put("roundId",roundId).put("assessmentSource",result.optString("engine","model")));
            if(result.optBoolean("pendingModel")&&modelEnabled) {stopped=true;progress="实习僧模型判断失败，未提交简历："+result.optString("reason");}
        },message->progress=message);
        if(!shouldStop())rankAndDeliver(c,roundId,manager.getBossPage());
    }

    void scanNativeSite(JSONObject c) {
        String roundId=store.beginRound(c.getInt("maxScan"));scheduledRoundId=roundId;
        nativeSites.collect(c,manager.getBossPage(),this::collectionEnded,job->{
            if(collectionEnded())return;String id=InternshipPolicy.hash(job.getString("url"));
            String formal=InternshipPolicy.formalReason(job);if(!formal.isBlank()){store.exclude(id,job,formal,roundId);return;}
            JSONObject result=analyze(c,job);if(collectionEnded())return;
            store.save(id,new JSONObject().put("job",job).put("result",result).put("status","screened").put("approved",false).put("roundId",roundId).put("settingsHash",InternshipPolicy.fingerprint(c)));
            if(result.optBoolean("pendingModel")&&modelEnabled){stopped=true;progress="模型判断失败，未提交简历："+result.optString("reason");}
        },message->progress=message);
        if(!shouldStop())rankAndDeliver(c,roundId,manager.getBossPage());
    }
    void rankAndDeliver(JSONObject c,String roundId,Page list) {
        phase="deliver";
        List<Map.Entry<String,JSONObject>> ranked=new ArrayList<>();JSONObject records=store.candidateRecords();
        for(String id:records.keySet()) {
            JSONObject entry=records.getJSONObject(id);if(roundId.equals(entry.optString("roundId")))ranked.add(Map.entry(id,entry));
        }
        ranked.sort((a,b)->InternshipPolicy.compareRanked(a.getValue(),b.getValue()));
        int rank=0;for(var item:ranked) {item.getValue().put("matchRank",++rank);store.save(item.getKey(),item.getValue());}
        if(!sendingEnabled||c.getString("mode").equals("scan"))return;
        int sent=0;
        for(var item:ranked) {
            if(sendWindowEnded()||sent>=c.getInt("maxSend"))break;
            String id=item.getKey();JSONObject entry=store.get(id),job=entry.getJSONObject("job"),result=entry.getJSONObject("result");
            if(store.blocksRetry(entry)||result.optString("decision").equals("skip")||result.optBoolean("pendingModel"))continue;
            if(scheduledAutoReview && result.optString("decision").equals("review")
                    && InternshipPolicy.manualReviewBlockReason(c,job).isBlank()) {
                store.confirmReview(id,"按用户预先授权由定时任务自动确认待确认岗位，未逐条人工审核","scheduled-user-authorization");
                store.approve(id);entry=store.get(id);
            }
            if(!InternshipPolicy.deliveryEligible(c,entry)||(!c.getString("mode").equals("auto")&&!entry.optBoolean("approved")))continue;
            if(!reserveDelivery(id,entry))continue;
            progress="按简历匹配度投递：第 "+entry.getInt("matchRank")+" 名，"+result.optInt("matchScore")+"/100 · "+job.optString("title");
            // A started delivery finishes its confirmation even across the slot boundary.
            // The single worker prevents the next platform from overlapping it.
            deliveryInFlight=true;
            try {if(sendCandidate(c,id,entry,list))sent++;} finally {deliveryInFlight=false;finishDelivery(id);}
            if(InternshipStore.attempted(store.get(id))&&!store.get(id).optString("status").equals("complete")) {
                stopped=true;progress="发送未完整确认，暂停并保留现场记录";
            }
            pause(c.getInt("intervalSeconds"));
        }
    }
    boolean sendCandidate(JSONObject c,String id,JSONObject entry,Page list) {
        if(entry.getJSONObject("job").optString("platform").equals("shixiseng"))return shixiseng.deliver(c,id,entry,list,this::shouldStop);
        if(!entry.getJSONObject("job").optString("platform","boss").equals("boss"))return nativeSites.deliver(c,id,entry,list,this::shouldStop);
        JSONObject job=entry.getJSONObject("job");Page detail=list.context().newPage();
        try {
            detail.setDefaultTimeout(10000);detail.setDefaultNavigationTimeout(25000);
            detail.navigate(navigationUrl(job));if(risk(detail))return false;
            if(!verifiedDetail(detail,job))throw new IllegalStateException("投递前岗位详情核对失败，未发送");
            return InternshipDelivery.send(store,id,entry,c.getString("greeting"),this::shouldStop,port(detail,job));
        } finally {closeDetailIfSafe(detail,id);}
    }

    private static JSONObject cardSummary(Locator link,String url) {
        Locator card=link.locator("xpath=ancestor::*[contains(@class,'job-card-wrap')][1]");
        if(card.count()==0)card=link;
        return new JSONObject().put("url",url).put("title",link.innerText().trim()).put("cardText",card.innerText())
                .put("salary",elementText(card,".salary,.job-salary,.salary-text"))
                .put("company",elementText(card,".company-name"));
    }

    // Keep native city/experience/degree/salary filters from the original configuration panel.
    private static String text(Page p, String selector) {
        Locator l = p.locator(selector); return l.count() == 0 ? "" : l.first().innerText().trim();
    }
    private JSONObject readJob(Page p, String url) {
        p.locator(".job-sec-text, .job-detail-body").first().waitFor();
        return new JSONObject().put("url", url).put("title", text(p, ".job-name h1, .job-name, h1"))
                .put("company", text(p, ".company-info .company-name, .company-name, .company-name a"))
                .put("recruiter", text(p, ".boss-info .name, .boss-name"))
                .put("city", text(p, ".job-area, .job-area-wrapper, .job-primary .info-primary p"))
                .put("salary",text(p,".job-detail-header .salary, .job-primary .salary, .salary"))
                .put("jd", p.locator(".job-sec-text, .job-detail-body").allInnerTexts().stream().reduce("", (a,b)->a+"\n"+b));
    }
    private JSONObject findCurrentJob(Page p,JSONObject expected) {
        String url=expected.getString("url"),relative=url.substring("https://www.zhipin.com".length());
        List<String> sources=new ArrayList<>(List.of("https://www.zhipin.com/web/geek/jobs"));
        BossConfig nativeConfig=configService.getBossConfig();
        var codes=nativeConfig.getCityCode();
        String code=codes==null || codes.isEmpty()?"100010000":codes.getFirst();
        String base=Boss.buildSearchUrl(nativeConfig,code);
        sources.add(base+(base.contains("?")?"&":"?")+"query="+URLEncoder.encode(expected.getString("title"),StandardCharsets.UTF_8));
        for(String source:sources) {
            p.navigate(source);
            if(risk(p)) throw new IllegalStateException("请先处理登录或安全验证");
            p.locator(".job-card-wrap, .job-card-wrapper").first().waitFor();internshipFilter(p);
            for(int i=0;i<8 && !shouldStop();i++) {
                if(p.locator("a[href^='"+relative+"']").count()>0)return readInlineJob(p,url);
                scrollList(p);p.waitForTimeout(500);
            }
        }
        throw new IllegalStateException("当前列表找不到原岗位，请重新采集最新岗位后投递");
    }
    private static void internshipFilter(Page page) {
        Locator filter=page.getByText(java.util.regex.Pattern.compile("^求职类型(?:\\s*[（(]\\d+[)）])?$"));
        if(filter.count()!=1)return;
        filter.click();
        try {page.getByText("实习",new Page.GetByTextOptions().setExact(true)).first()
                .click(new Locator.ClickOptions().setTimeout(1500));page.waitForTimeout(1200);}
        catch(PlaywrightException ignored) {page.keyboard().press("Escape");}
    }
    private static void scrollList(Page page) {
        page.evaluate("""
            () => {let el=document.querySelector('.job-card-wrap,.job-card-wrapper')?.parentElement;
            while(el && el!==document.body){if(el.scrollHeight>el.clientHeight+5 && /auto|scroll/.test(getComputedStyle(el).overflowY)){el.scrollBy(0,el.clientHeight*.9);return;}el=el.parentElement;}
            window.scrollBy(0,window.innerHeight*.9);}
            """);
    }
    private static String navigationUrl(JSONObject job) {
        String url=job.optString("detailUrl");
        if(!url.startsWith(job.getString("url")) || !(url.equals(job.getString("url")) || url.startsWith(job.getString("url")+"?")))
            throw new IllegalStateException("未找到当前岗位的完整详情入口，请重新采集");
        return url;
    }
    private static String compact(String value) {return value.replaceAll("\\s+","");}
    private static boolean verifiedDetail(Page page,JSONObject job) {
        if(!page.url().split("\\?")[0].equals(job.optString("url")))return false;
        String title=text(page,".job-name h1,h1");
        String banner=text(page,".job-banner,.job-primary");
        String company=text(page,".sider-company");
        if(!title.equals(job.optString("title")) || !company.contains(job.optString("company"))
                || !banner.contains(job.optString("city")) || !compact(banner).contains(compact(job.optString("salary"))))return false;
        if(job.optString("jd").isBlank() || job.optString("recruiter").isBlank())return false;
        String body=page.locator("body").innerText();
        return compact(body).contains(compact(job.getString("jd"))) && body.contains(job.getString("recruiter"));
    }
    private JSONObject withNavigation(Page p,JSONObject job) {
        for(Locator link:p.locator("a.more-job-btn").all()) {
            String href=link.getAttribute("href");
            if(href==null)continue;
            String url=href.startsWith("/")?"https://www.zhipin.com"+href:href;
            if(url.split("\\?")[0].equals(job.getString("url"))) {job.put("detailUrl",url);break;}
        }
        return job;
    }
    private JSONObject readInlineJob(Page p,String url) {
        String relative=url.substring("https://www.zhipin.com".length());
        Locator link=p.locator("a[href^='"+relative+"']").first();
        String expectedTitle=link.innerText().trim();
        Locator card=link.locator("xpath=ancestor::*[contains(@class,'job-card-wrap')][1]");
        if(card.count()==0) card=link;
        final Locator selected=card;
        String className=card.getAttribute("class");
        if(className!=null && className.contains("active")) {
            Locator other=p.locator(".job-card-wrap:not(.active)").first();
            if(other.count()>0) { other.click();p.waitForTimeout(500); }
        }
        Response response=null;
        try { response=p.waitForResponse(r->r.url().contains("/wapi/zpgeek/job/detail.json"),new Page.WaitForResponseOptions().setTimeout(5000),selected::click); }
        catch(PlaywrightException ignored) { /* Default-expanded card need not issue a second request. */ }
        if(response!=null) {
            JSONObject data=new JSONObject(response.text()).optJSONObject("zpData");
            JSONObject info=data==null?null:data.optJSONObject("jobInfo");
            if(info!=null && url.endsWith("/"+info.optString("encryptId")+".html")) {
                JSONObject brand=data.optJSONObject("brandComInfo"), boss=data.optJSONObject("bossInfo");
                return withNavigation(p,new JSONObject().put("url",url).put("title",info.optString("jobName"))
                    .put("jd",info.optString("postDescription")).put("salary",info.optString("salaryDesc"))
                    .put("city",info.optString("locationName")).put("employmentType",info.optString("jobTypeName"))
                    .put("experience",info.optString("experienceName")).put("degree",info.optString("degreeName"))
                    .put("company",brand==null?"":brand.optString("brandName"))
                    .put("recruiter",boss==null?"":boss.optString("name")));
            }
        }
        Locator root=p.locator(".job-detail-container").first();
        if(root.count()==0) throw new IllegalStateException("岗位详情未展开");
        String title=elementText(root,".job-detail-header .job-name, .job-name, h1");
        if(!title.equals(expectedTitle)) throw new IllegalStateException("展开的详情与所选岗位不一致，停止采集");
        return withNavigation(p,new JSONObject().put("url",url).put("title",title)
            .put("jd",elementText(root,".job-detail-body, .job-sec-text"))
            .put("salary",elementText(root,".salary"))
            .put("city",elementText(root,".job-area, .job-detail-info"))
            .put("company",elementText(root,".boss-info-attr, .company-name"))
            .put("recruiter",elementText(root,".boss-name, .boss-info .name")));
    }
    private static String elementText(Locator root,String selector) {
        Locator node=root.locator(selector);return node.count()==0?"":node.first().innerText().trim();
    }
    private boolean risk(Page p) {
        String url=p.url();
        String signals=text(p, ".geetest_panel, .captcha-container, .verify-dialog");
        boolean visibleVerification=p.locator(".geetest_panel, .captcha-container, .verify-dialog").all().stream().anyMatch(Locator::isVisible);
        String notice=p.locator(".dialog-content, .toast, .notice-message, .dialog-wrap, .dialog-container")
                .all().stream().filter(Locator::isVisible).map(Locator::innerText).collect(java.util.stream.Collectors.joining("\n"));
        if(InternshipPolicy.dailyCommunicationLimit(notice)) {
            stopped=true;progress="BOSS提示当日沟通次数已达上限，今天不再定时投递";
            store.scheduleQuotaReached(clock.instant().atZone(InternshipSchedule.ZONE).toLocalDate().toString(),progress);return true;
        }
        boolean loggedOut=p.locator("li.nav-sign").all().stream().anyMatch(l->l.isVisible() && l.innerText().contains("登录"))
                || p.getByText("登录查看完整内容",new Page.GetByTextOptions().setExact(true)).all().stream().anyMatch(Locator::isVisible);
        if(loggedOut) {
            stopped=true;progress="BOSS登录已失效，请在专用Chrome中重新登录";
            manager.setLoginStatus("boss",false);return true;
        }
        if (url.contains("passport") || url.contains("/login") || url.contains("verify") || !signals.isBlank()
                || visibleVerification || notice.matches("(?s).*(今日沟通.*上限|操作频繁|账号异常|安全验证).*")) {
            stopped=true; progress="登录或安全验证异常，请人工处理"; return true;
        }
        return false;
    }
    private static String conversationIdentity(Page page) {
        Locator conversation=page.locator(".chat-conversation").first();
        if(conversation.count()>0)return String.valueOf(conversation.evaluate("""
            el => {const header=el.cloneNode(true);header.querySelectorAll('.message-content,.chat-record,.chat-message,.chat-controls,.chat-im').forEach(e=>e.remove());return header.textContent;}
            """));
        return text(page,".chat-header, .chat-head, .chat-info, .boss-info");
    }
    InternshipDelivery.Port port(Page p, JSONObject job) {
        Boss helper=bossProvider.getObject();
        helper.setShouldStopCallback(this::shouldStop);
        return new InternshipDelivery.Port() {
            private boolean chat,newConversation;
            private boolean allowHistorical;
            private int historicalImageCount;
            public void allowPreviousDayMessages(boolean allow) {allowHistorical=allow;}
            private int ownImageCount() {return p.locator(".chat-conversation li.message-item.item-myself img.message-image, .chat-conversation li.message-item.item-myself .image-container img").count();}
            public boolean targetMatches() {
                if (shouldStop() || risk(p)) return false;
                if (!chat) return p.url().split("\\?")[0].equals(job.getString("url"))
                        && text(p,".job-name h1, .job-name, h1").equals(job.getString("title"));
                String identity=conversationIdentity(p);
                return !job.optString("recruiter").isBlank() && !job.optString("company").isBlank()
                        && identity.contains(job.getString("recruiter")) && identity.contains(job.getString("company"))
                        && identity.contains(job.getString("title"));
            }
            public boolean openChat() {
                if (shouldStop() || risk(p)) return false;
                Locator btn=p.locator("a.btn-startchat, a.op-btn-chat").first();
                newConversation=btn.innerText().contains("立即沟通");
                btn.click(); chat=true;
                if(p.url().contains("/job_detail/") && !targetMatches() && !helper.prepareFullImageChat(p,
                        ()->!shouldStop() && !risk(p) && (verifiedDetail(p,job)||targetMatches())))return false;
                p.locator("#chat-input[contenteditable='true'], textarea.input-area").first()
                        .waitFor(new Locator.WaitForOptions().setTimeout(30000));
                for(int i=0;i<150 && !shouldStop();i++) {
                    try {
                        if(risk(p))return false;
                        if(targetMatches()){historicalImageCount=ownImageCount();return true;}
                    } catch(PlaywrightException transientNavigation) {
                        if(p.isClosed())return false;
                    }
                    p.waitForTimeout(200);
                }
                return false;
            }
            public boolean sendAndConfirmText(String value) {
                if (!targetMatches()) return false;
                Locator root=p.locator(".chat-message .im-list, .chat-record").first();
                if (root.count()==0) return false;
                Locator own=root.locator("li.message-item.item-myself .message-content");
                if(newConversation && !allowHistorical && p.url().contains("/web/geek/chat")) {
                    for(int i=0;i<50 && own.count()==0 && !shouldStop();i++)p.waitForTimeout(200);
                    // BOSS sends its own default greeting on first contact. Never duplicate it.
                    return targetMatches() && own.all().stream().anyMatch(l->bubbleText(l).equals(value.trim()) && !failedBubble(l));
                }
                int before=own.count();
                // A prior identical outgoing message is ambiguous; reconcile manually instead of duplicating.
                if (!allowHistorical && own.all().stream().anyMatch(l->bubbleText(l).equals(value.trim()))) return false;
                Locator input=p.locator("#chat-input[contenteditable='true'], textarea.input-area").first();
                input.fill(value);
                if (shouldStop() || !targetMatches()) return false;
                p.locator("div.send-message, button[type='send'].btn-send, button.btn-send").first().click();
                for(int i=0;i<30 && !shouldStop();i++) {
                    if (!targetMatches()) return false;
                    if (own.count()>before && bubbleText(own.last()).equals(value.trim())) {
                        String state=String.valueOf(own.last().evaluate("el => { const item=el.closest('li'); return (item.className || '') + ' ' + Array.from(item.querySelectorAll('*')).map(n=>n.className || '').join(' ') + ' ' + (item.querySelector('.message-status')?.textContent || ''); }"));
                        if(state.matches("(?s).*(send-fail|send-error|message-fail|发送失败|重新发送|点击重试).*")) return false;
                        if(!state.matches("(?s).*(sending|send-progress|发送中).*")) return true;
                    }
                    p.waitForTimeout(500);
                }
                return false;
            }
            public boolean sendAndConfirmImage() {
                return !shouldStop() && targetMatches() && helper.sendImageResume(p, () -> !shouldStop() && targetMatches()) && targetMatches();
            }
            public boolean hasExistingImage() {
                return ownImageCount()>(allowHistorical?historicalImageCount:0);
            }
            public boolean confirmExistingText(String value) {
                return targetMatches() && p.locator(".chat-message .im-list li.message-item.item-myself .message-content, .chat-record li.message-item.item-myself .message-content")
                        .all().stream().anyMatch(l->bubbleText(l).equals(value.trim()) && !failedBubble(l));
            }
            public void returnToList() { /* Detail tab closes in the enclosing try; list position stays intact. */ }
        };
    }
    private static String bubbleText(Locator bubble) {
        return bubble.innerText().trim().replaceFirst("^(已送达|已发送|送达|已读|未读|发送中)\\s*","").trim();
    }
    private static boolean failedBubble(Locator bubble) {
        String state=String.valueOf(bubble.evaluate("el=>el.closest('li').className+' '+Array.from(el.closest('li').querySelectorAll('*')).map(n=>n.className||'').join(' ')+' '+el.innerText"));
        return state.matches("(?s).*(send-fail|send-error|message-fail|发送失败|重新发送|点击重试|sending|发送中).*" );
    }
    private void pause(int seconds) {
        for(int i=0;i<seconds*2 && !shouldStop();i++) {
            try { Thread.sleep(500); } catch(InterruptedException e) { Thread.currentThread().interrupt(); stopped=true; }
        }
    }
    private void closeDetailIfSafe(Page page,String id) {
        JSONObject saved=store.get(id);
        if(!InternshipStore.attempted(saved) || saved.optString("status").equals("complete")) page.close();
        // Leave ambiguous outgoing attempts open for the user to reconcile against the real conversation.
    }
}
