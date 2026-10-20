package com.getjobs.worker.boss;
import com.microsoft.playwright.*;
import org.json.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class InternshipShixisengTest {
    @TempDir Path directory;
    Playwright playwright;Browser browser;Page boss;InternshipStore store;InternshipShixiseng sxs;
    final String url="https://www.shixiseng.com/intern/inn_test1";
    String detail(boolean logged,String company,String button) {
        return "<div class='login-status'>"+(logged?"<div class='logined'><div class='mine'>账号</div><span class='nickname'>测试用户</span></div>":"登录/注册")+"</div>"
                +"<div class='new_job_name'><span>库存数据分析实习生</span></div><a class='com-name'>"+company+"</a><span class='job_money'>120-240/天</span>"
                +"<span class='job_position'>示例乙城</span><span class='job_week'>2天/周</span><span class='job_time'>3个月</span><div class='job_part'><div class='job_detail'>负责产品需求分析和PRD编写</div></div>"
                +"<button class='resume_apply' onclick=\"sessionStorage.submits=String(Number(sessionStorage.submits||0)+1);this.textContent='已投递'\">"+button+"</button>";
    }
    @BeforeEach void setup() {
        String executable=System.getProperty("internship.test-browser");Assumptions.assumeTrue(executable!=null&&Files.isRegularFile(Path.of(executable)));
        playwright=Playwright.create();browser=playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true).setExecutablePath(Path.of(executable)));
        var context=browser.newContext();context.route("**/*",route->route.abort());boss=context.newPage();store=new InternshipStore(directory);sxs=new InternshipShixiseng(store);
    }
    @AfterEach void close() {if(browser!=null)browser.close();if(playwright!=null)playwright.close();}
    void route(String html) {boss.context().route("https://www.shixiseng.com/**",r->r.fulfill(new Route.FulfillOptions().setContentType("text/html; charset=utf-8").setBody(html)));}
    JSONObject entry() {var e=new JSONObject().put("status","screened").put("job",new JSONObject().put("platform","shixiseng").put("url",url)
            .put("title","库存数据分析实习生").put("company","测试公司").put("salary","120-240/天").put("city","示例乙城").put("jd","负责产品需求分析和PRD编写\n2天/周 · 3个月"));store.save("native",e);return e;}
    @Test void nativeSubmissionConfirmsPlatformResumeWithoutPretendingImageOrChatWasSent() {
        route(detail(true,"测试公司","投个简历"));
        assertTrue(sxs.deliver(new JSONObject().put("shixisengResumeConfirmed",true),"native",entry(),boss,()->false));
        var saved=store.get("native");assertEquals("complete",saved.getString("status"));assertTrue(saved.getBoolean("nativeResumeConfirmed"));
        assertFalse(saved.optBoolean("textConfirmed"));assertFalse(saved.optBoolean("imageConfirmed"));
        assertEquals("1",sxs.page(boss).evaluate("()=>sessionStorage.submits"));
        assertTrue(store.shixisengState().getBoolean("liveSubmissionVerified"));
    }
    String nativeForm() {
        return "<div class='el-dialog'><div class='common-deliver'>"
            +"<div class='user-resume'><div class='user-resume__left'>在线简历</div><span class='right-item__left'>中文：61%</span><div class='right-item__img'><img src='/radio-choosed.png'></div></div>"
            +"<div class='user-resume'><div class='user-resume__left'>附件简历</div><span class='right-item__left' onclick=\"this.parentElement.querySelector('img').src='/radio-choosed.png'\">中文：92%</span><div class='right-item__img'><img src='/radio-choose.png'></div></div>"
            +"<div class='complete-info__item'>到岗时间<input value='2周内'></div>"
            +"<div class='complete-info__item'>实习时长<input value='3个月以内' onclick=\"document.querySelector('#period').style.display='block'\"></div>"
            +"<div class='complete-info__item'>每周出勤<input value='1天' onclick=\"document.querySelector('#days').style.display='block'\"></div>"
            +"<div class='common-deliver__footer'><div class='btn' onclick=\"sessionStorage.submits='1';document.querySelector('.resume_apply').textContent='已投递';this.closest('.el-dialog').remove()\">确认投递</div></div></div></div>"
            +"<div id='period' style='display:none'><div class='el-select-dropdown__item' onclick=\"document.querySelectorAll('.complete-info__item input')[1].value='6个月以上';this.parentElement.style.display='none'\">6个月以上</div></div>"
            +"<div id='days' style='display:none'><div class='el-select-dropdown__item' onclick=\"document.querySelectorAll('.complete-info__item input')[2].value='2天';this.parentElement.style.display='none'\">2天</div></div>";
    }
    @Test void selectsChineseAttachmentAndSavedAvailabilityBeforeFinalSubmission() {
        String html=detail(true,"测试公司","投个简历").replace("sessionStorage.submits=String(Number(sessionStorage.submits||0)+1);this.textContent='已投递'","this.nextElementSibling.style.display='block'")
            +"<div style='display:none'>"+nativeForm()+"</div>";
        route(html);
        JSONObject c=InternshipPolicy.defaults().put("shixisengResumeConfirmed",true).put("shixisengArrival","两周内").put("shixisengDays",2).put("shixisengPeriod","6个月以上").put("shixisengResumeType","attachment");
        assertTrue(sxs.deliver(c,"native",entry(),boss,()->false));
        assertEquals("attachment",store.get("native").getJSONObject("nativeForm").getString("resumeType"));
        assertEquals(2,store.get("native").getJSONObject("nativeForm").getInt("days"));
        assertEquals("6个月以上",store.get("native").getJSONObject("nativeForm").getString("period"));
    }
    @Test void unknownArrivalOrMissingChosenResumeDoesNotConfirm() {
        boss.setContent(nativeForm());
        assertThrows(IllegalStateException.class,()->InternshipShixiseng.prepareNativeForm(InternshipPolicy.defaults(),boss,boss.locator(".el-dialog")));
        boss.locator(".user-resume").last().evaluate("e=>e.remove()");
        assertThrows(IllegalStateException.class,()->InternshipShixiseng.prepareNativeForm(InternshipPolicy.defaults().put("shixisengArrival","一周内"),boss,boss.locator(".el-dialog")));
        assertEquals("确认投递",boss.locator(".common-deliver__footer .btn").innerText());
    }
    @Test void noLoginOrWrongIdentityNeverSubmits() {
        route(detail(false,"测试公司","投个简历"));assertThrows(IllegalStateException.class,()->sxs.deliver(new JSONObject().put("shixisengResumeConfirmed",true),"native",entry(),boss,()->false));
        assertEquals("screened",store.get("native").getString("status"));
        route(detail(true,"其他公司","投个简历"));assertThrows(IllegalStateException.class,()->sxs.deliver(new JSONObject().put("shixisengResumeConfirmed",true),"native",entry(),boss,()->false));
        assertNull(sxs.page(boss).evaluate("()=>sessionStorage.submits"));
    }
    @Test void unconfirmedNativeResumeNeverNavigatesOrSubmits() {
        assertThrows(IllegalStateException.class,()->sxs.deliver(new JSONObject(),"native",entry(),boss,()->false));assertNull(sxs.status().get("account"));
    }
    @Test void alreadySubmittedOnPlatformDoesNotClickAgain() {
        route(detail(true,"测试公司","已投递"));assertFalse(sxs.deliver(new JSONObject().put("shixisengResumeConfirmed",true),"native",entry(),boss,()->false));
        assertTrue(store.get("native").getBoolean("alreadySubmittedOnPlatform"));assertNull(sxs.page(boss).evaluate("()=>sessionStorage.submits"));
    }
    @Test void bothHomepageAndSearchIdentifiersYieldCanonicalPlatformUrls() {
        boss.setContent("<div class='intern-content-href' point-id='inn_test1'></div><div class='intern-wrap' data-intern-id='inn_test2'></div><a href='https://www.shixiseng.com/intern/inn_test1?from=search'>职位</a><a href='https://evil.example/intern/inn_wrong'>无效</a>");
        assertEquals(List.of(url,"https://www.shixiseng.com/intern/inn_test2"),InternshipShixiseng.urls(boss));
        assertFalse(InternshipShixiseng.validUrl("https://www.shixiseng.com.evil.example/intern/inn_test1"));
    }
    @Test void collectsRealDescriptionAndDailySalaryIntoSharedProfileFormat() {
        boss.setContent(detail(true,"测试公司","投个简历"));var job=InternshipShixiseng.readJob(boss,url);
        assertEquals("shixiseng",job.getString("platform"));assertTrue(job.getString("jd").contains("PRD"));assertEquals("120-240/天",job.getString("salary"));
        assertTrue(InternshipPolicy.formalReason(job).isBlank());
    }
    @Test void homepageWaitsForHydratedJobIdentifiersInsteadOfFailingOnEmptyCards() {
        boss.context().route("https://www.shixiseng.com/**",r->{
            String html=r.request().url().contains("/intern/")?detail(true,"测试公司","投个简历"):
                    "<div class='intern-content-href' point-id=''></div><script>setTimeout(()=>document.querySelector('.intern-content-href').setAttribute('point-id','inn_test1'),1500)</script>";
            r.fulfill(new Route.FulfillOptions().setContentType("text/html; charset=utf-8").setBody(html));
        });
        List<JSONObject> jobs=new ArrayList<>();var c=InternshipPolicy.defaults().put("source","recommend").put("maxScan",1).put("intervalSeconds",0);
        sxs.collect(c,boss,()->false,jobs::add,message->{});assertEquals(1,jobs.size());assertEquals(url,jobs.getFirst().getString("url"));
    }
}
