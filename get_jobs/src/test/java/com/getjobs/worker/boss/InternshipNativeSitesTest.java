package com.getjobs.worker.boss;

import com.microsoft.playwright.*;
import org.json.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class InternshipNativeSitesTest {
    @TempDir Path directory;
    Playwright playwright;Browser browser;Page page;
    String url(String p){return switch(p){case "zhilian"->"https://jobs.zhaopin.com/CC123456789.htm";case "job51"->"https://we.51job.com/pc/jobdetail?jobId=12345";default->"https://www.lagou.com/wn/jobs/12345.html";};}
    String html(String p,boolean login,String company){return (login?"<header><span class='user-name'>测试同学</span></header>":"<header>登录/注册</header>")
        +"<h1>库存分析实习生</h1><div class='company-name'>"+company+"</div><div class='salary'>120-240元/天</div><div class='job-city'>示例乙城</div>"
        +"<div class='job-description'>参与库存分析需求调研、用户访谈和PRD编写，协助产品设计和功能迭代，实习岗位，每周五天，三个月以上。</div>"
        +"<button onclick=\"sessionStorage.submitted=String(Number(sessionStorage.submitted||0)+1);this.textContent='已投递';document.querySelector('#receipt').textContent='投递成功'\">"+(p.equals("lagou")?"投递简历":"立即投递")+"</button><div id='receipt' class='delivery-success'></div>";}
    @BeforeEach void setup(){String executable=System.getProperty("internship.test-browser");Assumptions.assumeTrue(executable!=null&&Files.isRegularFile(Path.of(executable)));playwright=Playwright.create();browser=playwright.chromium().launch(new BrowserType.LaunchOptions().setExecutablePath(Path.of(executable)).setHeadless(true));var context=browser.newContext();context.route("**/*",r->r.abort());page=context.newPage();}
    @AfterEach void close(){if(browser!=null)browser.close();if(playwright!=null)playwright.close();}
    void route(String html){page.context().route("**/*",r->r.fulfill(new Route.FulfillOptions().setContentType("text/html; charset=utf-8").setBody(html)));}
    @Test void allThreePlatformsReadActualDescriptionAndConfirmNativeResumeWithoutChatImageClaims() {
        for(String p:List.of("zhilian","job51","lagou")) {
            route(html(p,true,"测试公司"));page.navigate(url(p));var store=new InternshipStore(directory.resolve(p));var adapter=new InternshipNativeSites(store,p,()->new JSONObject().put("resumeReady",true));
            var entry=new JSONObject().put("status","screened").put("job",adapter.readJob(page,url(p)));store.save("job",entry);
            assertTrue(adapter.deliver(new JSONObject(),"job",entry,page,()->false));assertEquals("complete",store.get("job").getString("status"));assertTrue(store.get("job").getBoolean("nativeResumeConfirmed"));assertFalse(store.get("job").optBoolean("imageConfirmed"));assertTrue(store.scheduleState().getJSONObject("nativeAcceptance").getBoolean("liveSubmissionVerified"));
        }
    }
    @Test void missingResumeOrLoginAndChangedIdentityNeverClickSubmit() {
        String p="job51";route(html(p,true,"测试公司"));page.navigate(url(p));var store=new InternshipStore(directory);var adapter=new InternshipNativeSites(store,p,()->new JSONObject().put("resumeReady",false));var entry=new JSONObject().put("status","screened").put("job",adapter.readJob(page,url(p)));store.save("job",entry);
        assertThrows(IllegalStateException.class,()->adapter.deliver(new JSONObject(),"job",entry,page,()->false));
        var permitted=new InternshipNativeSites(store,p,()->new JSONObject().put("resumeReady",true));
        route(html(p,false,"测试公司"));assertThrows(IllegalStateException.class,()->permitted.deliver(new JSONObject(),"job",entry,page,()->false));
        route(html(p,true,"另一公司"));assertThrows(IllegalStateException.class,()->permitted.deliver(new JSONObject(),"job",entry,page,()->false));
        assertEquals("screened",store.get("job").getString("status"));
    }
    String chatHtml(String company){return "<div class='im-session-item is-active'><span class='im-session-item__job'>库存分析实习生</span><span class='im-session-item__company-name'>"+company+"</span><span class='im-session-item__salary'>120-240元/天</span><div class='im-session-item__preview'>已发送附件简历</div></div><div class='im-chat-header'><span class='im-chat-header__meta-text'>"+company+"</span><span class='im-chat-header__job-title'>库存分析实习生</span><span class='im-chat-header__salary'>120-240元/天</span></div><div class='im-message im-message--custom'><div class='im-message__bubble im-message__bubble--me'>测试简历.pdf</div></div><button onclick=\"sessionStorage.resent='1'\">发简历</button>";}
    @Test void zhilianNativeClickCanFinishWithOwnAttachmentInMatchingChatRatherThanSuccessModal(){
        page.context().route("**/*",r->r.fulfill(new Route.FulfillOptions().setContentType("text/html; charset=utf-8").setBody(r.request().url().contains("/im")?chatHtml("测试公司"):html("zhilian",true,"测试公司").replace("this.textContent='已投递';document.querySelector('#receipt').textContent='投递成功'","location.href='https://i.zhaopin.com/im?sessionId=test'"))));
        page.navigate(url("zhilian"));var store=new InternshipStore(directory);var adapter=new InternshipNativeSites(store,"zhilian",()->new JSONObject().put("resumeReady",true));var entry=new JSONObject().put("status","screened").put("job",adapter.readJob(page,url("zhilian")));store.save("job",entry);
        assertTrue(adapter.deliver(new JSONObject(),"job",entry,page,()->false));assertEquals("complete",store.get("job").getString("status"));assertEquals("zhilian-chat-attachment",store.get("job").getString("nativeReceiptSource"));assertNull(page.evaluate("sessionStorage.resent"));
    }
    @Test void campusDisabledAppliedControlIsVerifiedWithoutClicking() {
        route(html("zhilian",true,"测试公司"));page.navigate(url("zhilian"));var store=new InternshipStore(directory);var adapter=new InternshipNativeSites(store,"zhilian",JSONObject::new);
        var entry=new JSONObject().put("status","native_attempted").put("job",adapter.readJob(page,url("zhilian")));store.save("job",entry);
        route(html("zhilian",true,"测试公司").replace("立即投递","已投递").replace("<button onclick=","<div class='job-banner__deliver__btn job-banner__deliver__btn--disabled' onclick=").replace("</button>","</div>"));
        assertEquals(false,((Map<?,?>)adapter.verifyExistingReceipt("job",entry,page,url("zhilian"))).get("newSubmission"));assertEquals("complete",store.get("job").getString("status"));assertNull(page.evaluate("sessionStorage.submitted"));
    }
    @Test void unselectedMatchingChatCanBeReadWithoutSendingAndAmbiguousOrWrongRowsAreNotClicked() {
        route(html("zhilian",true,"测试公司"));page.navigate(url("zhilian"));var store=new InternshipStore(directory);var adapter=new InternshipNativeSites(store,"zhilian",JSONObject::new);
        var job=adapter.readJob(page,url("zhilian"));var entry=new JSONObject().put("status","native_attempted").put("job",job);store.save("job",entry);
        String row=chatHtml("测试公司").replace("<span class='im-session-item__salary'>","<span class='im-session-item__salary' style='display:none'>").replace("sessionStorage.resent","localStorage.resent").replace("im-session-item is-active","im-session-item").replace("<div class='im-session-item'>","<div class='im-session-item' onclick=\"localStorage.selected='1';this.classList.add('is-active')\">");
        route(row.replace("测试公司","另一公司"));page.navigate("https://i.zhaopin.com/im");assertFalse(adapter.selectReceiptConversation(page,job));assertNull(page.evaluate("localStorage.selected"));
        route(row+row);page.reload();assertFalse(adapter.selectReceiptConversation(page,job));assertNull(page.evaluate("localStorage.selected"));
        route(row);assertEquals(true,((Map<?,?>)adapter.verifyExistingReceipt("job",entry,page,"https://i.zhaopin.com/im")).get("confirmed"));
        assertEquals("complete",store.get("job").getString("status"));assertEquals("1",page.evaluate("localStorage.selected"));assertNull(page.evaluate("localStorage.resent"));
    }
    @Test void persistedChatReceiptCanResolveUncertainSubmissionWithoutClickingOrAcceptingWrongConversation(){
        route(html("zhilian",true,"测试公司"));page.navigate(url("zhilian"));var store=new InternshipStore(directory);var adapter=new InternshipNativeSites(store,"zhilian",JSONObject::new);var job=adapter.readJob(page,url("zhilian"));var entry=new JSONObject().put("status","native_attempted").put("job",job);store.save("job",entry);
        for(String body:List.of(chatHtml("另一公司"),chatHtml("测试公司").replace("im-message__bubble--me","im-message__bubble--other"),chatHtml("测试公司").replace("已发送附件简历","新消息"),chatHtml("测试公司").replace("测试简历.pdf","发送中"))){route(body);page.navigate("https://i.zhaopin.com/im?sessionId=test");assertFalse(adapter.chatReceipt(page,job));}
        route(chatHtml("测试公司"));page.navigate("https://i.zhaopin.com/im?sessionId=test");
        assertThrows(IllegalStateException.class,()->adapter.verifyExistingReceipt("job",entry,page,"https://i.zhaopin.com.evil.example/im"));
        assertEquals(true,((Map<?,?>)adapter.verifyExistingReceipt("job",entry,page,"https://i.zhaopin.com/im?sessionId=test")).get("confirmed"));
        assertEquals("complete",store.get("job").getString("status"));assertFalse(store.scheduleState().getJSONObject("nativeAcceptance").getBoolean("liveSubmissionVerified"));assertNull(page.evaluate("sessionStorage.resent"));
    }
    @Test void missingAttachmentUsesResumeActionOnceOnlyAfterMatchingChatIdentity(){
        String chat=chatHtml("测试公司").replace("已发送附件简历","已打招呼").replace("im-message__bubble--me","im-message__bubble--other")
                .replace("<button onclick=\"sessionStorage.resent='1'\">", "<button class='im-send-resume-nav__chip' onclick=\"sessionStorage.resumeClicks=String(Number(sessionStorage.resumeClicks||0)+1);document.querySelector('.im-session-item__preview').textContent='已发送附件简历';document.querySelector('.im-message__bubble--other').className='im-message__bubble im-message__bubble--me'\">");
        page.context().route("**/*",r->r.fulfill(new Route.FulfillOptions().setContentType("text/html; charset=utf-8").setBody(r.request().url().contains("/im")?chat:html("zhilian",true,"测试公司").replace("this.textContent='已投递';document.querySelector('#receipt').textContent='投递成功'","location.href='https://i.zhaopin.com/im?sessionId=test'"))));
        page.navigate(url("zhilian"));var store=new InternshipStore(directory);var adapter=new InternshipNativeSites(store,"zhilian",()->new JSONObject().put("resumeReady",true));var entry=new JSONObject().put("status","screened").put("job",adapter.readJob(page,url("zhilian")));store.save("job",entry);
        assertTrue(adapter.deliver(new JSONObject(),"job",entry,page,()->false));assertEquals("1",page.evaluate("sessionStorage.resumeClicks"));assertTrue(store.get("job").getBoolean("nativeResumeActionStarted"));assertTrue(store.scheduleState().getJSONObject("nativeAcceptance").getBoolean("liveSubmissionVerified"));
    }
    @Test void continuingUncertainZhilianUsesExistingConversationPopupWithoutRepeatingInitialApply(){
        String detail=html("zhilian",true,"测试公司").replace("立即投递","继续沟通").replace("this.textContent='已投递';document.querySelector('#receipt').textContent='投递成功'","window.open('https://i.zhaopin.com/im?sessionId=test','existingIm')");
        page.context().route("**/*",r->r.fulfill(new Route.FulfillOptions().setContentType("text/html; charset=utf-8").setBody(r.request().url().contains("/im")?chatHtml("测试公司"):detail)));
        page.navigate(url("zhilian"));var store=new InternshipStore(directory);var adapter=new InternshipNativeSites(store,"zhilian",()->new JSONObject().put("resumeReady",true));var entry=new JSONObject().put("status","native_attempted").put("job",adapter.readJob(page,url("zhilian")));store.save("job",entry);
        Page existing=page.waitForPopup(()->page.evaluate("window.open('https://i.zhaopin.com/im?sessionId=test','existingIm')"));existing.waitForLoadState();
        assertTrue(adapter.continueExistingDelivery("job",entry,page,()->false));assertEquals("1",page.evaluate("sessionStorage.submitted"));assertEquals(0,adapter.applyButtons(page).size());assertEquals("complete",store.get("job").getString("status"));assertTrue(store.scheduleState().getJSONObject("nativeAcceptance").getBoolean("liveSubmissionVerified"));assertEquals(2,page.context().pages().size());
        assertNull(page.context().pages().getLast().evaluate("sessionStorage.resent"));
    }
    @Test void firstZhilianApplyThenContinueOpensReceiptWithoutRepeatingApplyOrSendingAnotherResume(){
        String action="if(this.textContent==='立即投递'){sessionStorage.initial=String(Number(sessionStorage.initial||0)+1);this.textContent='继续沟通'}else{sessionStorage.continued=String(Number(sessionStorage.continued||0)+1);window.open('https://i.zhaopin.com/im?sessionId=test','_blank')}";
        String detail=html("zhilian",true,"测试公司").replace("sessionStorage.submitted=String(Number(sessionStorage.submitted||0)+1);this.textContent='已投递';document.querySelector('#receipt').textContent='投递成功'",action);
        page.context().route("**/*",r->r.fulfill(new Route.FulfillOptions().setContentType("text/html; charset=utf-8").setBody(r.request().url().contains("/im")?chatHtml("测试公司"):detail)));
        page.navigate(url("zhilian"));var store=new InternshipStore(directory);var adapter=new InternshipNativeSites(store,"zhilian",()->new JSONObject().put("resumeReady",true));var entry=new JSONObject().put("status","screened").put("job",adapter.readJob(page,url("zhilian")));store.save("job",entry);
        assertTrue(adapter.deliver(new JSONObject(),"job",entry,page,()->false));assertEquals("1",page.evaluate("sessionStorage.initial"));assertEquals("1",page.evaluate("sessionStorage.continued"));assertTrue(store.get("job").getBoolean("nativeConversationOpened"));assertTrue(store.scheduleState().getJSONObject("nativeAcceptance").getBoolean("liveSubmissionVerified"));assertNull(page.context().pages().getLast().evaluate("sessionStorage.resent"));
    }
    @Test void resumeInspectionReadsOnlyKnownCenterAndNeverSubmitsOrChangesResumeReadiness() {
        route("<div class='user-container'><div class='user-header'>测试同学</div></div><h1>简历中心</h1><p>中文附件简历 已上传</p><button onclick=\"sessionStorage.clicked='1'\">申请职位</button>");
        page.navigate("https://www.51job.com/");var store=new InternshipStore(directory);var config=new JSONObject().put("resumeReady",false);var adapter=new InternshipNativeSites(store,"job51",()->config);
        var result=(Map<?,?>)adapter.inspectDetail(page,"https://www.51job.com/resume/center");
        assertTrue(result.get("resumePageText").toString().contains("中文附件简历 已上传"));assertFalse(config.getBoolean("resumeReady"));assertNull(page.evaluate("sessionStorage.clicked"));assertEquals(1,page.context().pages().size());
        assertThrows(IllegalArgumentException.class,()->adapter.inspectDetail(page,"https://www.51job.com/resume/center?redirect=https://example.com"));
        assertThrows(IllegalArgumentException.class,()->adapter.inspectDetail(page,"https://www.51job.com.evil.example/resume/center"));
    }
    @Test void securityChallengeRemainsOpenForManualResolutionWithoutSubmission(){
        route("<h1>安全验证</h1><p>请完成验证后继续</p><button onclick=\"sessionStorage.submitted='1'\">申请职位</button>");page.navigate("https://www.51job.com/");
        var adapter=new InternshipNativeSites(new InternshipStore(directory),"job51",JSONObject::new);var result=(Map<?,?>)adapter.inspectDetail(page,url("job51"));
        assertEquals(true,result.get("requiresUserAction"));assertEquals(2,page.context().pages().size());assertFalse(page.context().pages().getLast().isClosed());assertNull(page.evaluate("sessionStorage.submitted"));
    }
    @Test void dailyQuotaIsScopedToCurrentPlatform() {
        route(html("job51",true,"测试公司")+"今日投递次数已达上限");page.navigate(url("job51"));var store=new InternshipStore(directory);var adapter=new InternshipNativeSites(store,"job51",JSONObject::new);
        assertThrows(IllegalStateException.class,()->adapter.readJob(page,url("job51")));var states=store.scheduleState().getJSONObject("platforms");assertTrue(states.getJSONObject("job51").has("quotaDate"));assertFalse(states.getJSONObject("zhilian").has("quotaDate"));
    }
    @Test void canonicalUrlsRejectOtherPlatformsAndRetainOnlyRequiredJobIdentifier() {
        assertEquals(url("job51"),InternshipNativeSites.canonical("job51",url("job51")+"&tracking=abc"));
        assertEquals("",InternshipNativeSites.canonical("zhilian",url("job51")));assertEquals("",InternshipNativeSites.canonical("zhilian","https://jobs.zhaopin.com.evil.example/CC1.htm"));assertEquals("",InternshipNativeSites.canonical("lagou","https://evil@www.lagou.com/wn/jobs/123.html"));
        assertEquals("https://www.zhaopin.com/jobdetail/CC123456789.htm",InternshipNativeSites.canonical("zhilian",url("zhilian")));
        assertEquals(InternshipNativeSites.canonical("zhilian",url("zhilian")),InternshipNativeSites.canonical("zhilian","https://www.zhaopin.com/jobdetail/CC123456789.html?from=search"));
        assertEquals(InternshipNativeSites.canonical("zhilian",url("zhilian")),InternshipNativeSites.canonical("zhilian","https://xiaoyuan.zhaopin.com/job/CC123456789?refcode=4032"));
        assertEquals("",InternshipNativeSites.canonical("zhilian","https://xiaoyuan.zhaopin.com.evil.example/job/CC123456789"));
    }
    @Test void sourceNeverSilentlyReplacesHomepageWithKeywordSearch() {
        var adapter=new InternshipNativeSites(new InternshipStore(directory),"lagou",JSONObject::new);var c=InternshipPolicy.defaults().put("source","recommend");assertEquals(List.of("https://www.lagou.com/"),adapter.listingUrls(c));c.put("source","search").put("keywords",new JSONArray().put("库存分析实习"));assertTrue(adapter.listingUrls(c).getFirst().contains("kd="));
    }
    @Test void safetyVerificationIsReportedInsteadOfPretendingOnlyLoginIsMissing() {
        route("<title>滑动验证页面</title><h1>访问验证</h1>");var adapter=new InternshipNativeSites(new InternshipStore(directory),"lagou",JSONObject::new);
        var status=adapter.checkSession(page);assertEquals(false,status.get("loggedIn"));assertEquals(true,status.get("requiresUserAction"));assertTrue(status.get("message").toString().contains("安全验证"));
    }
    @Test void unrelatedSubmittedBadgeAndNegativeReceiptNeverConfirmCurrentApplication() {
        var adapter=new InternshipNativeSites(new InternshipStore(directory),"job51",JSONObject::new);
        route("<button>已投递</button><button class='apply-button'>立即投递</button><div class='delivery-success'>未投递成功</div>");page.navigate(url("job51"));assertFalse(adapter.completed(page));
    }
    @Test void observedJob51HeaderRetainsExperienceAndReadsCompanyOnlyFromMatchingPageMetadata() {
        String title="库存分析实习生招聘 | 示例乙城 | 120-240元/天 | 测试有限公司 | 前程无忧";
        String html="<title>"+title+"</title><div class='tHeader'><div class='cn'><h1>库存分析实习生</h1><strong>120-240元/天</strong><p class='msg'><span class='type_2'>示例乙城</span><span class='type_3'>3-5年</span></p></div><div class='apply-btn-new'>立即投递</div></div><div class='job_msg'>负责产品需求分析与用户研究，参与产品迭代和数据分析。</div>";
        route(html);page.navigate(url("job51"));var adapter=new InternshipNativeSites(new InternshipStore(directory),"job51",JSONObject::new);var job=adapter.readJob(page,url("job51"));
        assertEquals("测试有限公司",job.getString("company"));assertEquals("示例乙城",job.getString("city"));assertEquals("3-5年",job.getString("experience"));assertFalse(InternshipPolicy.formalReason(job).isBlank());assertEquals(1,adapter.applyButtons(page).size());
        route(html.replace("<h1>库存分析实习生</h1>","<h1>库存分析实习生 (职位编号：A133100)</h1>"));page.navigate(url("job51"));assertEquals("测试有限公司",adapter.readJob(page,url("job51")).getString("company"));
    }
    @Test void job51UsesVerifiedHeaderApplyControlInsteadOfDuplicateStickyActions(){
        route("<div class='tHeader'><div class='apply-btn-new big'>立即投递</div></div><div class='sticky'><div class='apply-btn-new'>立即投递</div></div>");page.navigate(url("job51"));var adapter=new InternshipNativeSites(new InternshipStore(directory),"job51",JSONObject::new);
        assertEquals(1,adapter.applyButtons(page).size());assertEquals("apply-btn-new big",adapter.applyButtons(page).getFirst().getAttribute("class"));
    }
    String job51HistoryHtml(){String day=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString();return "<section><a href='https://jobs.51job.com/all/12345.html'>库存分析实习生</a><p>测试公司</p><p>120-240元/天</p><p>申请于"+day+"</p><p>申请简历：默认在线简历</p></section><button onclick=\"sessionStorage.resent='1'\">再次申请</button>";}
    @Test void job51HistoryRequiresSameJobCompanyCurrentDayAndResumeWithinOneRow(){
        route(html("job51",true,"测试公司"));page.navigate(url("job51"));var store=new InternshipStore(directory);var adapter=new InternshipNativeSites(store,"job51",JSONObject::new);var job=adapter.readJob(page,url("job51"));
        String history=job51HistoryHtml(),today=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString();
        for(String invalid:List.of(history.replace("/12345.html","/99999.html"),history.replace("测试公司","另一公司"),history.replace(today,"2000-01-01"),history.replace("默认在线简历",""),history.replace("<p>测试公司</p>","<a href='https://jobs.51job.com/all/67890.html'>另一岗位</a><p>测试公司</p>"))){route(invalid);page.navigate("https://i.51job.com/userset/my_apply.php");assertTrue(adapter.job51HistoryReceipt(page,job).isEmpty());}
        route(history);page.navigate("https://i.51job.com/userset/my_apply.php");var entry=new JSONObject().put("status","native_attempted").put("job",job);store.save("job",entry);
        assertEquals(false,((Map<?,?>)adapter.verifyExistingReceipt("job",entry,page,"https://i.51job.com/userset/my_apply.php")).get("newSubmission"));assertTrue(store.get("job").getBoolean("nativeResumeConfirmed"));assertEquals("默认在线简历",store.get("job").getJSONObject("nativeReceiptEvidence").getString("resumeName"));assertNull(page.evaluate("sessionStorage.resent"));
    }
    @Test void job51ApplyResultRedirectChecksActualHistoryRatherThanTreatingUrlAsSuccess(){
        page.context().route("**/*",r->{String request=r.request().url();String body=request.contains("my_apply.php")?job51HistoryHtml():request.contains("applysuccess.php")?"<p>使用微信扫码与HR沟通</p>":html("job51",true,"测试公司").replace("this.textContent='已投递';document.querySelector('#receipt').textContent='投递成功'","location.href='https://jobs.51job.com/applysuccess.php?jobid=12345'");r.fulfill(new Route.FulfillOptions().setContentType("text/html; charset=utf-8").setBody(body));});
        page.navigate(url("job51"));var store=new InternshipStore(directory);var adapter=new InternshipNativeSites(store,"job51",()->new JSONObject().put("resumeReady",true));var entry=new JSONObject().put("status","screened").put("job",adapter.readJob(page,url("job51")));store.save("job",entry);
        assertTrue(adapter.deliver(new JSONObject(),"job",entry,page,()->false));assertEquals("job51-application-history",store.get("job").getString("nativeReceiptSource"));assertTrue(store.scheduleState().getJSONObject("nativeAcceptance").getBoolean("liveSubmissionVerified"));assertEquals(1,page.context().pages().size());
    }
    @Test void externalSuccessTextNeverConfirmsAnotherPlatformOrClicksExternalControls(){
        page.context().route("**/*",r->r.fulfill(new Route.FulfillOptions().setContentType("text/html; charset=utf-8").setBody(r.request().url().contains("external.example")?"<div class='delivery-success'>投递成功</div><button onclick=\"sessionStorage.externalClicked='1'\">确认投递</button>":html("job51",true,"测试公司").replace("this.textContent='已投递';document.querySelector('#receipt').textContent='投递成功'","location.href='https://external.example/form'"))));
        page.navigate(url("job51"));var store=new InternshipStore(directory);var adapter=new InternshipNativeSites(store,"job51",()->new JSONObject().put("resumeReady",true));var entry=new JSONObject().put("status","screened").put("job",adapter.readJob(page,url("job51")));store.save("job",entry);
        assertThrows(IllegalStateException.class,()->adapter.deliver(new JSONObject(),"job",entry,page,()->false));assertEquals("native_attempted",store.get("job").getString("status"));assertFalse(store.get("job").optBoolean("nativeResumeConfirmed"));assertNull(page.evaluate("sessionStorage.externalClicked"));
    }
    @Test void wrongZhilianConversationCannotSubmitEvenWithCheckedResumeDialog(){
        String wrong=chatHtml("另一公司")+"<div role='dialog'><input type='radio' checked><button onclick=\"sessionStorage.wrongSend='1'\">确认投递</button></div>";
        page.context().route("**/*",r->r.fulfill(new Route.FulfillOptions().setContentType("text/html; charset=utf-8").setBody(r.request().url().contains("/im")?wrong:html("zhilian",true,"测试公司").replace("this.textContent='已投递';document.querySelector('#receipt').textContent='投递成功'","location.href='https://i.zhaopin.com/im?sessionId=wrong'"))));
        page.navigate(url("zhilian"));var store=new InternshipStore(directory);var adapter=new InternshipNativeSites(store,"zhilian",()->new JSONObject().put("resumeReady",true));var entry=new JSONObject().put("status","screened").put("job",adapter.readJob(page,url("zhilian")));store.save("job",entry);var checks=new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(IllegalStateException.class,()->adapter.deliver(new JSONObject(),"job",entry,page,()->checks.incrementAndGet()>5));assertNull(page.evaluate("sessionStorage.wrongSend"));assertFalse(store.get("job").optBoolean("nativeResumeConfirmed"));
    }
    @Test void oneIncompleteDetailDoesNotConsumeCandidateQuotaOrBlockLaterReadableJob() {
        String bad=url("job51"),good="https://we.51job.com/pc/jobdetail?jobId=67890";
        page.context().route("**/*",r->{String request=r.request().url();String body=request.contains("/pc/search")?"<div class='joblist'>"+job51Card(bad,"岗位一","120元/天")+job51Card(good,"岗位二","120元/天")+"</div>":request.contains("12345")?"<h1>无法核对企业的岗位</h1><div class='job-description'>负责产品用户调研和需求分析，参与产品迭代和日常运营。</div>":html("job51",true,"测试公司");r.fulfill(new Route.FulfillOptions().setContentType("text/html; charset=utf-8").setBody(body));});
        var store=new InternshipStore(directory);store.beginRound(1);var adapter=new InternshipNativeSites(store,"job51",JSONObject::new);List<JSONObject> jobs=new ArrayList<>();
        adapter.collect(InternshipPolicy.defaults().put("source","search").put("keywords",new JSONArray().put("库存分析实习")).put("maxScan",1),page,()->false,jobs::add,s->{});
        assertEquals(1,jobs.size());assertEquals(good,jobs.getFirst().getString("url"));assertEquals(1,store.roundSummary().getInt("readFailed"));assertEquals(1,store.readFailures().length());
    }
    @Test void safetyVerificationInDetailStopsRoundBeforeOtherJobsInsteadOfSilentlySkipping() {
        String bad=url("job51");page.context().route("**/*",r->r.fulfill(new Route.FulfillOptions().setContentType("text/html; charset=utf-8").setBody(r.request().url().contains("/pc/search")?"<div class='joblist'>"+job51Card(bad,"实习岗位","120元/天")+"</div>":"<h1>访问验证</h1>")));
        var store=new InternshipStore(directory);store.beginRound(1);var adapter=new InternshipNativeSites(store,"job51",JSONObject::new);
        assertThrows(IllegalStateException.class,()->adapter.collect(InternshipPolicy.defaults().put("source","search").put("keywords",new JSONArray().put("库存分析实习")),page,()->false,j->{fail("不能接收未验证岗位");},s->{}));assertEquals(1,store.readFailures().length());assertEquals(2,page.context().pages().size());
    }
    String job51Card(String url,String title,String salary){return "<div class='joblist-item'><div class='joblist-item-jobname'><div class='job-info' onclick=\"sessionStorage.cardClicks=String(Number(sessionStorage.cardClicks||0)+1);window.open('"+url+"','_blank')\">"+title+"</div></div><div class='joblist-item-jobinfo'><span class='sal'>"+salary+"</span></div><button onclick=\"sessionStorage.applied='1'\">投递</button></div>";}
    @Test void job51PartnerMappingRequiresExactHostAndConsistentNumericJobIdentity(){
        assertEquals("https://jobs.51job.com/all/67890.html",InternshipNativeSites.job51PartnerJobUrl("https://q.yingjiesheng.com/pc/jobdetail?jobid=67890&partner=test"));
        assertEquals("https://jobs.51job.com/all/67890.html",InternshipNativeSites.job51PartnerJobUrl("https://q.yingjiesheng.com/jobdetail/67890.html?jobid=67890"));
        for(String url:List.of("https://q.yingjiesheng.com/jobdetail/67890.html?jobid=99999","https://q.yingjiesheng.com.evil.test/pc/jobdetail?jobid=67890","http://q.yingjiesheng.com/pc/jobdetail?jobid=67890","https://q.yingjiesheng.com/pc/jobdetail?jobid=abc"))assertEquals("",InternshipNativeSites.job51PartnerJobUrl(url));
    }
    @Test void job51PartnerCardReturnsToSameJobOn51jobWithoutApplyingOnPartner(){
        String partner="https://q.yingjiesheng.com/pc/jobdetail?jobid=67890&partner=test";
        String listing="<div class='joblist'>"+job51Card(partner,"库存分析实习生","120-240元/天").replace("window.open('"+partner+"','_blank')","window.open('"+partner+"','_blank','noopener,noreferrer')")+"</div>";
        page.context().route("**/*",r->{String u=r.request().url();if(u.endsWith("/slow.png"))return;
            r.fulfill(new Route.FulfillOptions().setContentType("text/html; charset=utf-8").setBody(u.contains("/pc/search")?listing:u.startsWith("https://q.yingjiesheng.com/")?"<img src='https://q.yingjiesheng.com/slow.png'>":html("job51",true,"测试公司")));});
        var store=new InternshipStore(directory);store.beginRound(1);var adapter=new InternshipNativeSites(store,"job51",JSONObject::new);List<JSONObject> jobs=new ArrayList<>();
        adapter.collect(InternshipPolicy.defaults().put("source","search").put("keywords",new JSONArray().put("库存分析实习")).put("maxScan",1),page,()->false,jobs::add,s->{});
        assertEquals("https://jobs.51job.com/all/67890.html",jobs.getFirst().getString("url"));assertNull(page.evaluate("sessionStorage.applied"));
    }
    @Test void job51SearchWaitsForDelayedPopupNavigation(){
        String good="https://we.51job.com/pc/jobdetail?jobId=67890";
        String card=job51Card(good,"库存分析实习生","120元/天").replace("window.open('"+good+"','_blank')","(()=>{let w=window.open('about:blank','_blank');setTimeout(()=>w.location.href='"+good+"',500)})()");
        page.context().route("**/*",r->r.fulfill(new Route.FulfillOptions().setContentType("text/html; charset=utf-8").setBody(r.request().url().contains("/pc/search")?"<div class='joblist'>"+card+"</div>":html("job51",true,"测试公司"))));
        var store=new InternshipStore(directory);store.beginRound(1);var adapter=new InternshipNativeSites(store,"job51",JSONObject::new);List<JSONObject> jobs=new ArrayList<>();
        adapter.collect(InternshipPolicy.defaults().put("source","search").put("keywords",new JSONArray().put("库存分析实习")).put("maxScan",1),page,()->false,jobs::add,s->{});
        assertEquals(good,jobs.getFirst().getString("url"));assertTrue(store.readFailures().isEmpty());assertNull(page.evaluate("sessionStorage.applied"));
    }
    @Test void job51SearchReadsActualCardsAndNeverUsesUnrelatedFooterRecommendationsOrApplyButtons(){
        String good="https://we.51job.com/pc/jobdetail?jobId=67890";
        String listing="<div class='joblist'>"+job51Card(url("job51"),"正式数据分析师","1.5-2万")+job51Card(good,"库存分析实习生","120元/天")+"</div><footer><a href='"+url("job51")+"'>无关推荐岗位</a></footer>";
        page.context().route("**/*",r->r.fulfill(new Route.FulfillOptions().setContentType("text/html; charset=utf-8").setBody(r.request().url().contains("/pc/search")?listing:html("job51",true,"测试公司"))));
        var store=new InternshipStore(directory);store.beginRound(1);var adapter=new InternshipNativeSites(store,"job51",JSONObject::new);List<JSONObject> jobs=new ArrayList<>();
        adapter.collect(InternshipPolicy.defaults().put("source","search").put("keywords",new JSONArray().put("库存分析 实习")).put("maxScan",1),page,()->false,jobs::add,s->{});
        assertEquals(good,jobs.getFirst().getString("url"));assertEquals(1,store.roundSummary().getInt("preExcluded"));assertEquals("1",page.evaluate("sessionStorage.cardClicks"));assertNull(page.evaluate("sessionStorage.applied"));assertEquals(1,page.context().pages().size());
    }
    @Test void detailDiagnosticWithoutHeadingReportsVerificationInsteadOfThrowingTimeout() {
        route("<title>访问验证</title><div>请完成安全验证后继续</div>");
        var adapter=new InternshipNativeSites(new InternshipStore(directory),"lagou",JSONObject::new);
        var result=(Map<?,?>)adapter.inspectDetail(page,url("lagou"));
        assertEquals(true,result.get("requiresUserAction"));assertTrue(result.get("jobError").toString().contains("安全验证"));assertEquals(List.of(),result.get("headerAncestors"));assertFalse(result.containsKey("job"));
    }
    @Test void observedAccountHeadersAreRecognizedWithoutMistakingLoginOrRecruiterTextForAccount() {
        var zhilian=new InternshipNativeSites(new InternshipStore(directory.resolve("zl")),"zhilian",JSONObject::new);
        var job51=new InternshipNativeSites(new InternshipStore(directory.resolve("51")),"job51",JSONObject::new);
        route("<div class='home-header__c-login'><span class='c-login__top__name'>测试同学</span></div><div class='user-container'><div class='user-header'>Hi，测试同学</div></div>");page.navigate("https://www.zhaopin.com/");
        assertTrue(zhilian.loggedIn(page));assertTrue(job51.loggedIn(page));
        route("<div class='header-nav__c-login'><span class='c-login__top__name'>测试同学</span></div>");page.navigate("https://www.zhaopin.com/jobdetail/CC12345.htm");assertTrue(zhilian.loggedIn(page));
        route("<div class='user-info el-popover__reference'>测试同学</div>");page.navigate(url("job51"));assertTrue(job51.loggedIn(page));
        assertEquals(List.of(InternshipNativeSites.ZHILIAN_CAMPUS_RECOMMEND),zhilian.listingUrls(InternshipPolicy.defaults().put("source","recommend")));
        route("<div class='home-header__c-no-login'>登录/注册</div><div class='user-info'>招聘联系人</div><div class='user-container'><div class='user-header' style='display:none'>旧账号</div></div>");page.navigate("https://www.51job.com/");
        assertFalse(zhilian.loggedIn(page));assertFalse(job51.loggedIn(page));
    }
    @Test void currentZhilianDetailReadsDescriptionRatherThanHeaderAndIgnoresRelatedJobSalary(){
        String body="<div class='job-detail'><h1 class='summary-planes__title'>库存分析实习生</h1><span class='summary-planes__salary'>120-240元/天</span><ul class='summary-planes__info'><li><a class='workCity-link'>示例乙城</a></li><li>5-10年</li><li>本科</li></ul></div><a class='company-info__name'>测试公司</a><div class='describtion-card__detail-content'>参与产品需求分析、用户访谈和PRD编写，协助产品设计和功能迭代。</div><span class='jobs-deliver__salary'>5-8万</span>";
        route(body);page.navigate("https://www.zhaopin.com/jobdetail/CC123456789.htm");var adapter=new InternshipNativeSites(new InternshipStore(directory),"zhilian",JSONObject::new);var job=adapter.readJob(page,url("zhilian"));
        assertEquals("测试公司",job.getString("company"));assertEquals("120-240元/天",job.getString("salary"));assertEquals("示例乙城",job.getString("city"));assertEquals("5-10年",job.getString("experience"));assertTrue(job.getString("jd").startsWith("参与产品需求"));assertFalse(InternshipPolicy.formalReason(job).isBlank());
    }
    @Test void splitListAdvancesPastFormalCardsAndReadsSelectedJobInsteadOfDefaultSidebar(){
        String listing="<div class='job-list-panel'><div class='job-card job-card--active'><div class='job-card__title-main'>数据分析师</div><span class='job-card__salary'>1.5-2.5万</span><span class='job-card__skill-tag'>5-10年</span></div><div class='job-card'><div class='job-card__title-main' onclick=\"sessionStorage.cardClicks=String(Number(sessionStorage.cardClicks||0)+1);document.querySelector('#full').href='/jobdetail/CC67890.htm'\">库存分析实习生</div><span class='job-card__salary'>120-240元/天</span><span class='job-card__skill-tag'>经验不限</span></div></div><a id='full' href='/jobdetail/CC12345.htm'>查看更多职位信息</a>";
        page.context().route("**/*",r->r.fulfill(new Route.FulfillOptions().setContentType("text/html; charset=utf-8").setBody(r.request().url().contains("/sou/")?listing:html("zhilian",true,"测试公司"))));
        var store=new InternshipStore(directory);store.beginRound(1);var adapter=new InternshipNativeSites(store,"zhilian",JSONObject::new);List<JSONObject> jobs=new ArrayList<>();
        adapter.collect(InternshipPolicy.defaults().put("source","search").put("keywords",new JSONArray().put("库存分析实习")).put("maxScan",1),page,()->false,jobs::add,s->{});
        assertEquals(1,jobs.size());assertEquals("https://www.zhaopin.com/jobdetail/CC67890.htm",jobs.getFirst().getString("url"));assertEquals(1,store.roundSummary().getInt("preExcluded"));assertEquals("1",page.evaluate("sessionStorage.cardClicks"));
    }
    String campusCard(String id,String salary,String type){return "<div class='position-card'><div class='position-card__job-name' onclick=\"if(!window.internshipSelected)throw Error('未选择实习');sessionStorage.cardClicks=String(Number(sessionStorage.cardClicks||0)+1);window.open('/job/"+id+"','_blank')\">产品岗位</div><span class='position-card__salary'>"+salary+"</span><span class='position-card__tags__item'>"+type+"</span><div class='job-apply' onclick=\"sessionStorage.applyClicks='1'\">立即投递</div></div>";}
    @Test void campusRecommendationSelectsInternshipBeforeReadingPopupAndPreservesCanonicalDedupIdentity(){
        String listing="<style>.app-select__list{display:none}.app-select:hover .app-select__list{display:block}</style><div class='page-header__user__logged-in'>测试同学</div><div class='app-select'><div class='app-select__slot'>工作性质</div><div class='app-select__list'><div onclick=\"document.querySelector('.app-select__slot').textContent='实习';window.internshipSelected=true\">实习</div></div></div>"+campusCard("CC11111","6000元/月","实习")+campusCard("CC67890","120-240元/天","实习")+campusCard("CC99999","面议","校园");
        String detail="<h1 class='job-banner__name'>产品岗位<img><span class='job-banner__salary'>120-240元/天</span></h1><p class='job-banner__desc'><span class='job-banner__desc-text'>示例乙城</span><span class='job-banner__desc-text'>本科</span><span class='job-banner__desc-text'>实习</span></p><h2 class='company-info__name'>测试公司</h2><div class='job-info__feature'>总实习月数：3个月；周实习天数：5天</div><div class='job-info__desc'>参与产品用户访谈、需求分析与产品方案设计，协助功能迭代和项目推进。</div><div class='job-info__desc'>公司简介不属于岗位职责</div>";
        page.context().route("**/*",r->r.fulfill(new Route.FulfillOptions().setContentType("text/html; charset=utf-8").setBody(r.request().url().contains("xiaoyuan.zhaopin.com/recommend")?listing:r.request().url().contains("/job/")?detail:"<div>暂无求职意向</div>")));
        var store=new InternshipStore(directory);store.beginRound(1);var adapter=new InternshipNativeSites(store,"zhilian",JSONObject::new);List<JSONObject> jobs=new ArrayList<>();
        adapter.collect(InternshipPolicy.defaults().put("source","recommend").put("maxScan",1),page,()->false,jobs::add,s->{});
        assertEquals(1,jobs.size());var job=jobs.getFirst();assertEquals("https://www.zhaopin.com/jobdetail/CC67890.htm",job.getString("url"));assertEquals("https://xiaoyuan.zhaopin.com/job/CC67890",job.getString("navigationUrl"));assertEquals("产品岗位",job.getString("title"));assertEquals("实习",job.getString("employmentType"));assertEquals("示例乙城",job.getString("city"));assertTrue(job.getString("jd").contains("3个月"));assertFalse(job.getString("jd").contains("公司简介"));
        assertEquals(1,store.roundSummary().getInt("preExcluded"));assertEquals("1",page.evaluate("sessionStorage.cardClicks"));assertNull(page.evaluate("sessionStorage.applyClicks"));assertEquals(1,page.context().pages().size());
        var original=new JSONObject(job.toString());job.put("employmentType","全职");assertFalse(InternshipNativeSites.same(original,job));
        var guarded=new InternshipNativeSites(store,"zhilian",()->new JSONObject().put("resumeReady",true));var entry=new JSONObject().put("job",original.put("navigationUrl","https://xiaoyuan.zhaopin.com/job/CCWRONG"));
        assertThrows(IllegalArgumentException.class,()->guarded.deliver(new JSONObject(),"job",entry,page,()->false));assertNull(page.evaluate("sessionStorage.applyClicks"));
    }
}
