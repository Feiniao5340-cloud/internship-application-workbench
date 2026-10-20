package com.getjobs.worker.boss;

import com.getjobs.application.service.*;
import com.microsoft.playwright.*;
import org.json.JSONObject;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import java.nio.file.*;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

/** Local fixture only; all HTTP/network access is aborted. No recruitment account is used. */
class InternshipBrowserTest {
    @TempDir Path directory;
    Playwright playwright; Browser browser; Page page; Boss helper; InternshipRunner runner;
    JSONObject job=new JSONObject().put("title","库存分析实习生").put("company","测试公司").put("recruiter","测试HR").put("url","about:blank");
    @BeforeEach @SuppressWarnings("unchecked") void setup() {
        String executable=System.getProperty("internship.test-browser");
        Assumptions.assumeTrue(executable!=null && Files.isRegularFile(Path.of(executable)),"Pass a local browser executable for fixture tests");
        playwright=Playwright.create();
        browser=playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true).setExecutablePath(Path.of(executable)));
        BrowserContext context=browser.newContext();context.route("**/*",route->route.abort());page=context.newPage();
        helper=mock(Boss.class);var provider=mock(ObjectProvider.class);when(provider.getObject()).thenReturn(helper);
        runner=new InternshipRunner(new InternshipStore(directory),mock(AiService.class),null,provider,mock(BossResumeImageService.class),null);
        page.setContent("""
            <h1>库存分析实习生</h1><div class='job-name'><h1>库存分析实习生</h1></div>
            <a class='btn-startchat' href='#' onclick='document.querySelector("#chat").hidden=false;return false'>立即沟通</a>
            <div id='chat' hidden><div class='chat-header'>测试HR 测试公司 库存分析实习生</div>
            <div id='chat-input' class='chat-input' contenteditable='true'></div>
            <div class='chat-message'><ul class='im-list'></ul></div>
            <button class='btn-send' onclick='sendText()'>发送</button></div>
            <script>function sendText(){const node=document.createElement('li');node.className='message-item item-myself';
            const content=document.createElement('div');content.className='message-content';content.textContent=document.querySelector('#chat-input').textContent;
            node.append(content);document.querySelector('.im-list').append(node)}</script>
            """);
    }
    @AfterEach void close(){if(browser!=null)browser.close();if(playwright!=null)playwright.close();}
    @Test void realDomTextThenImageAndIdentityChecks() {
        var port=runner.port(page,job);assertTrue(port.targetMatches());assertTrue(port.openChat());assertTrue(port.targetMatches());
        assertTrue(port.sendAndConfirmText("测试招呼语"));assertTrue(port.confirmExistingText("测试招呼语"));
        when(helper.sendImageResume(eq(page),any())).thenAnswer(call->{
            assertTrue(((java.util.function.BooleanSupplier)call.getArgument(1)).getAsBoolean());return true;});
        assertTrue(port.sendAndConfirmImage());
        page.locator(".chat-header").evaluate("el=>el.textContent='另一个HR 测试公司 库存分析实习生'");
        assertFalse(port.targetMatches());assertFalse(port.sendAndConfirmImage());verify(helper,times(1)).sendImageResume(eq(page),any());
    }
    @Test void oldTextBubbleIsNotNewDeliveryConfirmation() {
        var port=runner.port(page,job);port.openChat();
        assertTrue(port.sendAndConfirmText("相同招呼语"));assertFalse(port.sendAndConfirmText("相同招呼语"));
        assertEquals(1,page.locator("li.item-myself").count());
    }
    @Test void delayedChatIdentityIsAllowedWithoutRelaxingTargetChecks() {
        page.locator(".chat-header").evaluate("el=>el.textContent=''");
        page.locator(".btn-startchat").evaluate("""
            el=>el.onclick=()=>{document.querySelector('#chat').hidden=false;
                setTimeout(()=>document.querySelector('.chat-header').textContent='测试HR 测试公司 库存分析实习生',11000);return false;}
            """);
        var port=runner.port(page,job);assertTrue(port.openChat());assertTrue(port.targetMatches());
        assertEquals(0,page.locator("li.item-myself").count());
        page.locator(".chat-header").evaluate("el=>el.textContent='测试HR 其他公司 库存分析实习生'");
        assertFalse(port.targetMatches());
    }
    @Test void firstLightweightGreetingReopensFullChatAndReallyUploadsImage() throws Exception {
        lightweightToImage(1);
    }
    @Test void repeatedLightweightPopupStillUploadsOnceWithoutAnotherGreeting() throws Exception {
        lightweightToImage(2);
    }
    @SuppressWarnings("unchecked") void lightweightToImage(int popupCount) throws Exception {
        String url="https://www.zhipin.com/job_detail/lightweight.html",chat="https://www.zhipin.com/web/geek/chat";
        String jd="负责产品需求分析和PRD。招募实习生。",greeting="测试招呼语";
        page.context().route(url,route->route.fulfill(new Route.FulfillOptions().setContentType("text/html; charset=utf-8").setBody("""
            <div class='job-name'><h1>库存分析实习生</h1></div><div class='job-banner'>示例甲城 240元/天</div>
            <div class='sider-company'>测试公司</div><div class='boss-info'>测试HR</div><p>负责产品需求分析和PRD。招募实习生。</p>
            <a class='btn-startchat' onclick='enterChat()'>立即沟通</a>
            <div id='light' hidden><span>测试HR 测试公司</span><p>测试招呼语</p><textarea placeholder='请简短描述您的问题'></textarea>
            <i class='icon-close' onclick="document.querySelector('#light').hidden=true">关闭</i></div>
            <script>sessionStorage.greetings='0';sessionStorage.entries='0';
            function enterChat(){const n=Number(sessionStorage.entries)+1;sessionStorage.entries=String(n);
              if(n<=POPUP_COUNT){document.querySelector('#light').hidden=false;if(n===1)sessionStorage.greetings='1';
                if(n===POPUP_COUNT)document.querySelector('.btn-startchat').textContent='继续沟通';}
              else location.href='/web/geek/chat';}</script>
            """.replace("POPUP_COUNT",Integer.toString(popupCount)))));
        page.context().route(chat,route->route.fulfill(new Route.FulfillOptions().setContentType("text/html; charset=utf-8").setBody("""
            <div class='chat-conversation'><div class='receiver'>测试HR 测试公司</div><div class='chat-position-content'>库存分析实习生</div>
            <div class='chat-record'><div class='chat-message'><ul class='im-list'><li class='message-item item-myself'>
            <div class='message-content'><span class='message-status'>送达</span><p>测试招呼语</p></div></li></ul></div></div>
            <div class='chat-controls'><div id='chat-input' contenteditable='true'></div>
            <div class='btn-sendimg' aria-label='发送图片'><input type='file' accept='image/gif,image/jpeg,image/png' onchange='upload(this)'></div>
            <button class='btn-send' onclick="throw Error('不应重复发送文字')">发送</button></div></div>
            <script>function upload(input){const item=document.createElement('li');item.className='message-item item-myself';
            const content=document.createElement('div');content.className='message-content';const img=document.createElement('img');
            img.className='message-image';img.src=URL.createObjectURL(input.files[0]);content.append(img);
            const status=document.createElement('span');status.className='message-status status-delivery';status.textContent='送达';
            content.append(status);item.append(content);document.querySelector('.im-list').append(item)}</script>
            """)));
        Path image=directory.resolve("resume.png");
        Files.write(image,java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a3ioAAAAASUVORK5CYII="));
        var images=mock(BossResumeImageService.class);when(images.getCurrentImagePath()).thenReturn(java.util.Optional.of(image));
        var actualHelper=new Boss(mock(BossService.class),mock(AiService.class),images);
        var provider=mock(ObjectProvider.class);when(provider.getObject()).thenReturn(actualHelper);
        var store=new InternshipStore(directory);var actualRunner=new InternshipRunner(store,mock(AiService.class),null,provider,images,null);
        var target=new JSONObject(job.toString()).put("url",url).put("jd",jd).put("salary","240元/天").put("city","示例甲城");
        var entry=new JSONObject().put("job",target).put("status","screened");store.save("light",entry);page.navigate(url);
        assertTrue(InternshipDelivery.send(store,"light",entry,greeting,()->false,actualRunner.port(page,target)));
        assertEquals("complete",store.get("light").getString("status"));
        assertEquals("1",page.evaluate("()=>sessionStorage.greetings"));
        assertEquals(popupCount+1,Integer.parseInt(page.evaluate("()=>sessionStorage.entries").toString()));
        assertEquals(2,page.locator("li.item-myself").count());assertEquals(1,page.locator("li.item-myself img.message-image").count());
        assertEquals("resume.png",page.locator("input[type=file]").evaluate("el=>el.files[0].name"));
    }
    @Test void differentRoleAtSameRecruiterIsRejected() {
        var port=runner.port(page,job);port.openChat();
        page.locator(".chat-header").evaluate("el=>el.textContent='测试HR 测试公司 仓库作业实习生'");
        assertFalse(port.targetMatches());assertFalse(port.sendAndConfirmText("测试招呼语"));
    }
    @Test void pausedRunnerDoesNotClickSend() {
        var port=runner.port(page,job);port.openChat();runner.stop();
        assertFalse(port.sendAndConfirmText("测试招呼语"));assertEquals(0,page.locator("li.item-myself").count());
    }
    @Test void authorizedNextDaySendUsesNewTextAndIgnoresOnlyImageBaseline() {
        page.locator("#chat").evaluate("el=>el.classList.add('chat-conversation')");
        var first=runner.port(page,job);assertTrue(first.openChat());assertTrue(first.sendAndConfirmText("测试招呼语"));
        page.locator("li.item-myself .message-content").evaluate("el=>{const img=document.createElement('img');img.className='message-image';el.append(img)}");
        var next=runner.port(page,job);next.allowPreviousDayMessages(true);assertTrue(next.openChat());
        assertFalse(next.hasExistingImage(),"上个自然日的历史图片不阻止已授权的新一天投递");
        assertTrue(next.sendAndConfirmText("测试招呼语"));assertEquals(2,page.locator("li.item-myself").count());
        page.locator("li.item-myself .message-content").last().evaluate("el=>{const img=document.createElement('img');img.className='message-image';el.append(img)}");
        assertTrue(next.hasExistingImage(),"打开会话后新增的图片仍须阻止重复上传");
    }
    @Test void failedBubbleDoesNotConfirmText() {
        var port=runner.port(page,job);port.openChat();
        page.locator("button.btn-send").evaluate("el=>el.onclick=()=>{sendText();document.querySelector('li.item-myself').classList.add('send-fail')} ");
        assertFalse(port.sendAndConfirmText("测试招呼语"));
    }
    @Test void nativeAutomaticGreetingAndStatusAreConfirmedWithoutDuplicateSending() {
        String url="https://www.zhipin.com/web/geek/chat";
        page.context().route(url,route->route.fulfill(new Route.FulfillOptions().setContentType("text/html; charset=utf-8").setBody("""
            <h1>库存分析实习生</h1><aside>测试HR 测试公司 库存分析实习生</aside>
            <a class='btn-startchat' onclick="document.querySelector('.chat-conversation').hidden=false;sendText()">立即沟通</a>
            <div class='chat-conversation' hidden><div class='receiver'>测试HR 测试公司</div>
            <div class='chat-position-content'>库存分析实习生</div>
            <div class='chat-record'><div class='chat-message'><ul class='im-list'></ul></div></div>
            <div id='chat-input' contenteditable='true'></div><button class='btn-send' onclick='sendText()'>发送</button></div>
            <script>function sendText(){document.querySelector('.im-list').insertAdjacentHTML('beforeend',
            '<li class="message-item item-myself"><div class="message-content"><span class="message-status">送达</span><p>测试招呼语</p></div></li>')}</script>
            """)));
        page.navigate(url);var port=runner.port(page,new JSONObject(job.toString()).put("url",url));
        assertTrue(port.openChat());assertTrue(port.targetMatches());
        assertTrue(port.sendAndConfirmText("测试招呼语"));assertTrue(port.confirmExistingText("测试招呼语"));
        assertEquals(1,page.locator("li.item-myself").count());
        page.locator(".receiver").evaluate("el=>el.textContent='测试HR 另一公司'");
        assertFalse(port.targetMatches());assertFalse(port.confirmExistingText("测试招呼语"));
    }
    @Test @SuppressWarnings("unchecked") void searchAndRecommendationScanReadJdWithoutCommunicating() {
        var ai=mock(AiService.class);var manager=mock(com.getjobs.worker.manager.PlaywrightManager.class);
        when(manager.getBossPage()).thenReturn(page);
        var config=mock(ConfigService.class);when(config.getBossConfig()).thenReturn(new BossConfig());
        var provider=mock(ObjectProvider.class);when(provider.getObject()).thenReturn(helper);
        var store=new InternshipStore(directory);
        var scanner=new InternshipRunner(store,ai,manager,provider,mock(BossResumeImageService.class),config);
        org.springframework.test.util.ReflectionTestUtils.setField(scanner,"modelEnabled",true);
        String jd="负责产品需求分析和PRD编写。招募实习生。";
        JSONObject answer=new JSONObject().put("decision","apply").put("category","库存分析实习")
                .put("confidence",0.95).put("matchScore",85).put("reason","依据职责和候选人经历匹配")
                .put("jdEvidence",new org.json.JSONArray().put("产品需求分析"))
                .put("resumeEvidence",new org.json.JSONArray().put("PRD"))
                .put("unknowns",new org.json.JSONArray()).put("constraintViolations",new org.json.JSONArray());
        when(ai.sendRequest(any())).thenReturn(answer.toString());
        List<String> requested=new java.util.ArrayList<>();
        page.context().route("https://www.zhipin.com/**",route->{
            String url=route.request().url();requested.add(url);
            if(url.contains("/wapi/zpgeek/job/detail.json")) {
                JSONObject info=new JSONObject().put("encryptId","test").put("jobName","库存分析实习生")
                    .put("postDescription",jd).put("salaryDesc","240元/天").put("locationName","示例甲城");
                route.fulfill(new Route.FulfillOptions().setContentType("application/json").setBody(new JSONObject().put("zpData",new JSONObject()
                    .put("jobInfo",info).put("brandComInfo",new JSONObject().put("brandName","测试公司"))
                    .put("bossInfo",new JSONObject().put("name","测试HR"))).toString()));return;
            }
            String html=url.contains("/job_detail/")
                    ? "<div class='job-name'><h1>库存分析实习生</h1></div><div class='company-name'>测试公司</div><div class='boss-info'><span class='name'>测试HR</span></div><div class='job-area'>示例甲城</div><div class='job-sec-text'>"+jd+"</div><a class='btn-startchat'>立即沟通</a>"
                    : "<div class='job-card-wrap' onclick=\"fetch('/wapi/zpgeek/job/detail.json');return false\"><a href='/job_detail/test.html'>岗位</a><a href='/job_detail/test.html'>重复岗位</a></div>";
            route.fulfill(new Route.FulfillOptions().setContentType("text/html; charset=utf-8").setBody(html));
        });
        var c=InternshipPolicy.defaults().put("confirmed",true).put("resumeText","有PRD经历")
                .put("targetDirections","库存分析实习").put("keywords",new org.json.JSONArray().put("产品"))
                .put("maxScan",1).put("intervalSeconds",0);
        scanner.scan(c);assertEquals(1,store.records().length());verify(ai,times(1)).sendRequest(any());
        assertEquals("screened",store.records().getJSONObject(store.records().keys().next()).getString("status"));
        scanner.scan(c.put("source","recommend"));verify(ai,times(1)).sendRequest(any());
        assertTrue(requested.stream().anyMatch(url->url.contains("query=")));
        assertTrue(requested.stream().anyMatch(url->url.endsWith("/web/geek/jobs")));
        verify(helper,never()).sendImageResume(any(),any());
    }
    @Test @SuppressWarnings("unchecked") void autoDeliveryUsesLiveDetailEntryAndRecordsBothConfirmations() {
        scanAndDeliver("apply",false);
    }
    @Test void scheduledReviewDeliversWithPriorAuthorization() {scanAndDeliver("review",true);}
    @SuppressWarnings("unchecked") void scanAndDeliver(String decision,boolean scheduled) {
        var ai=mock(AiService.class);var manager=mock(com.getjobs.worker.manager.PlaywrightManager.class);
        when(manager.getBossPage()).thenReturn(page);
        var provider=mock(ObjectProvider.class);when(provider.getObject()).thenReturn(helper);
        var store=new InternshipStore(directory);
        var scanner=new InternshipRunner(store,ai,manager,provider,mock(BossResumeImageService.class),null);
        org.springframework.test.util.ReflectionTestUtils.setField(scanner,"modelEnabled",true);
        org.springframework.test.util.ReflectionTestUtils.setField(scanner,"sendingEnabled",true);
        String jd="负责产品需求分析和PRD编写。招募实习生。";
        when(ai.sendRequest(any())).thenReturn(new JSONObject().put("decision",decision).put("category","库存分析实习")
                .put("confidence",0.95).put("matchScore",85).put("reason","产品职责与PRD经历匹配")
                .put("jdEvidence",new org.json.JSONArray().put("产品需求分析"))
                .put("resumeEvidence",new org.json.JSONArray().put("PRD"))
                .put("unknowns",new org.json.JSONArray()).put("constraintViolations",new org.json.JSONArray()).toString());
        String chatHtml=page.content();List<String> requests=new java.util.ArrayList<>();
        page.context().route("https://www.zhipin.com/**",route->{
            String url=route.request().url();requests.add(url);
            if(url.contains("/wapi/zpgeek/job/detail.json")) {
                var info=new JSONObject().put("encryptId","test").put("jobName","库存分析实习生").put("postDescription",jd)
                        .put("salaryDesc","240元/天").put("locationName","示例甲城");
                route.fulfill(new Route.FulfillOptions().setContentType("application/json").setBody(new JSONObject()
                        .put("zpData",new JSONObject().put("jobInfo",info)
                                .put("brandComInfo",new JSONObject().put("brandName","测试公司"))
                                .put("bossInfo",new JSONObject().put("name","测试HR"))).toString()));return;
            }
            String html=url.contains("/job_detail/") ? chatHtml+"<div class='job-banner'>示例甲城 240元/天</div><div class='sider-company'>测试公司</div><div>测试HR "+jd+"</div>" :
                    "<div class='job-card-wrap' onclick=\"fetch('/wapi/zpgeek/job/detail.json');return false\"><a href='/job_detail/test.html'>库存分析实习生</a></div>"
                    +"<a class='more-job-btn' href='/job_detail/test.html?securityId=fixture'>更多详情</a>";
            route.fulfill(new Route.FulfillOptions().setContentType("text/html; charset=utf-8").setBody(html));
        });
        when(helper.sendImageResume(any(),any())).thenAnswer(call->((java.util.function.BooleanSupplier)call.getArgument(1)).getAsBoolean());
        var c=InternshipPolicy.defaults().put("confirmed",true).put("resumeText","有PRD经历").put("targetDirections","库存分析实习")
                .put("source","recommend").put("mode","auto").put("greeting","测试招呼语").put("maxScan",1).put("intervalSeconds",0);
        store.saveSettings(new JSONObject(c.toString()).put("intervalSeconds",5));
        org.springframework.test.util.ReflectionTestUtils.setField(scanner,"scheduledAutoReview",scheduled);
        scanner.scan(c);
        var record=store.get(InternshipPolicy.hash("https://www.zhipin.com/job_detail/test.html"));
        assertEquals(decision,record.getJSONObject("result").getString("decision"),record.toString());
        if(scheduled)assertEquals("scheduled-user-authorization",record.getJSONObject("humanReview").getString("origin"));
        assertEquals("complete",record.getString("status"),record.toString()+" requests="+requests);assertTrue(record.getBoolean("textConfirmed"));assertTrue(record.getBoolean("imageConfirmed"));
        assertTrue(requests.stream().anyMatch(url->url.contains("/job_detail/test.html?securityId=fixture")));
        verify(helper,times(1)).sendImageResume(any(),any());
        // Persisted completion must be respected by new rounds and both sources, without rereading JD or sending.
        var nativeConfig=mock(ConfigService.class);when(nativeConfig.getBossConfig()).thenReturn(new BossConfig());
        var restarted=new InternshipRunner(new InternshipStore(directory),ai,manager,provider,mock(BossResumeImageService.class),nativeConfig);
        org.springframework.test.util.ReflectionTestUtils.setField(restarted,"sendingEnabled",true);
        org.springframework.test.util.ReflectionTestUtils.setField(restarted,"modelEnabled",true);
        for(String source:List.of("search","recommend"))restarted.scan(new JSONObject(c.toString()).put("source",source).put("keywords",new org.json.JSONArray().put("产品")));
        verify(ai,times(1)).sendRequest(any());verify(helper,times(1)).sendImageResume(any(),any());
        assertEquals("complete",new InternshipStore(directory).get(InternshipPolicy.hash("https://www.zhipin.com/job_detail/test.html")).getString("status"));
        var nextDayStore=new InternshipStore(directory);nextDayStore.clock=java.time.Clock.offset(java.time.Clock.systemUTC(),java.time.Duration.ofDays(1));
        var nextDayScanner=new InternshipRunner(nextDayStore,ai,manager,provider,mock(BossResumeImageService.class),nativeConfig);
        org.springframework.test.util.ReflectionTestUtils.setField(nextDayScanner,"sendingEnabled",true);
        org.springframework.test.util.ReflectionTestUtils.setField(nextDayScanner,"modelEnabled",true);
        org.springframework.test.util.ReflectionTestUtils.setField(nextDayScanner,"scheduledAutoReview",scheduled);
        nextDayScanner.scan(new JSONObject(c.toString()).put("source","search").put("keywords",new org.json.JSONArray().put("产品")));
        verify(ai,times(2)).sendRequest(any());verify(helper,times(2)).sendImageResume(any(),any());
        nextDayScanner.scan(new JSONObject(c.toString()).put("source","recommend"));
        verify(helper,times(2)).sendImageResume(any(),any());
    }
    @Test @SuppressWarnings("unchecked") void formalCardsAndHiddenProfessionalRequirementsDoNotConsumeCandidateQuota(){
        var ai=mock(AiService.class);var manager=mock(com.getjobs.worker.manager.PlaywrightManager.class);when(manager.getBossPage()).thenReturn(page);
        var provider=mock(ObjectProvider.class);when(provider.getObject()).thenReturn(helper);var store=new InternshipStore(directory);
        var scanner=new InternshipRunner(store,ai,manager,provider,mock(BossResumeImageService.class),null);
        org.springframework.test.util.ReflectionTestUtils.setField(scanner,"modelEnabled",true);
        when(ai.sendRequest(any())).thenReturn(new JSONObject().put("decision","apply").put("category","库存分析实习").put("confidence",0.95).put("matchScore",85)
                .put("reason","产品需求职责匹配").put("jdEvidence",new org.json.JSONArray().put("产品需求分析"))
                .put("resumeEvidence",new org.json.JSONArray().put("PRD")).put("unknowns",new org.json.JSONArray())
                .put("constraintViolations",new org.json.JSONArray()).toString());
        var detailReads=new java.util.ArrayList<String>();StringBuilder html=new StringBuilder("<meta charset='utf-8'>");
        html.append("<button onclick=\"document.querySelector('#types').hidden=false\">求职类型(1)</button><div id='types' hidden><button onclick=\"document.body.dataset.internship='yes';document.querySelector('#types').hidden=true\">实习</button></div>");
        for(int i=0;i<20;i++)html.append("<div class='job-card-wrap'><a href='/job_detail/monthly"+i+".html'>数据分析师</a><span class='salary'>\ue034\ue031K</span><p>3-5年</p></div>");
        html.append("<div class='job-card-wrap'><a href='/job_detail/years.html'>数据分析师</a><span class='salary'>240元/天</span><p>3-5年</p></div>");
        for(String id:new String[]{"hidden","valid"})html.append("<div class='job-card-wrap' onclick=\"fetch('/wapi/zpgeek/job/detail.json?id="+id+"');return false\"><a href='/job_detail/"+id+".html'>库存分析实习生</a><span class='salary'>240元/天</span><p>在校/应届</p></div>");
        page.context().route("https://www.zhipin.com/**",route->{
            if(!route.request().url().contains("/wapi/")){route.fulfill(new Route.FulfillOptions().setContentType("text/html; charset=utf-8").setBody(html.toString()));return;}
            String id=route.request().url().endsWith("hidden")?"hidden":"valid";detailReads.add(id);
            String jd=id.equals("hidden")?"招募库存分析实习生，需3年以上互联网产品工作经验。":"招募实习生，负责产品需求分析及PRD编写。";
            var info=new JSONObject().put("encryptId",id).put("jobName","库存分析实习生").put("postDescription",jd)
                    .put("salaryDesc","240元/天").put("locationName","示例甲城").put("experienceName","在校/应届");
            route.fulfill(new Route.FulfillOptions().setContentType("application/json").setBody(new JSONObject().put("zpData",new JSONObject()
                    .put("jobInfo",info).put("brandComInfo",new JSONObject().put("brandName","测试公司"))
                    .put("bossInfo",new JSONObject().put("name","测试HR"))).toString()));
        });
        scanner.scan(InternshipPolicy.defaults().put("confirmed",true).put("resumeText","有PRD经历").put("targetDirections","库存分析实习")
                .put("source","recommend").put("maxScan",1).put("intervalSeconds",0));
        assertEquals(java.util.List.of("hidden","valid"),detailReads);verify(ai,times(1)).sendRequest(any());
        assertEquals(1,store.candidateRecords().length());assertEquals(1,store.roundSummary().getInt("total"));
        assertEquals(22,store.roundSummary().getInt("preExcluded"));assertEquals(1,store.roundSummary().getInt("apply"));
        assertEquals("yes",page.locator("body").getAttribute("data-internship"));verify(helper,never()).sendImageResume(any(),any());
    }
}
