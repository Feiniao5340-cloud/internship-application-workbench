package com.getjobs.worker.boss;

import com.getjobs.worker.parallel.Platforms;
import com.microsoft.playwright.*;
import org.json.*;
import java.net.URI;
import java.util.*;
import java.util.function.*;

/** Separate adapter; the retired 51job delivery proof cannot authorize this site's submission. */
public class InternshipYingjiesheng extends InternshipNativeSites {
    public static final String ADAPTER="yingjiesheng-intern-v1";
    static final String SEARCH="https://q.yingjiesheng.com/jobs/search/?pageCode=internship%7Csearch%7Cjobsearchlb";
    private final InternshipStore store;
    private final Supplier<JSONObject> configuration;
    public InternshipYingjiesheng(InternshipStore store,Supplier<JSONObject> configuration){super(store,"job51",configuration);this.store=store;this.configuration=configuration;}
    @Override public Map<String,Object> checkSession(Page page){page.navigate(Platforms.home("job51"));page.bringToFront();for(int n=0;n<30&&!loggedIn(page);n++)page.waitForTimeout(200);try{check(page);}catch(Attention e){return Map.of("loggedIn",false,"requiresUserAction",true,"message",e.getMessage(),"persistent",true);}boolean ready=loggedIn(page);return Map.of("loggedIn",ready,"message",ready?"已识别应届生登录态":"请在独立Chrome手动登录应届生，再检查登录态","persistent",true,"adapter",ADAPTER);}
    @Override public Map<String,Object> diagnostic(Page page){
        URI u=URI.create(page.url().split("[?#]",2)[0]);if(!"q.yingjiesheng.com".equals(u.getHost()))return Map.of("message","请打开应届生实习入口","loggedIn",false);
        Map<String,Object> r=new LinkedHashMap<>();r.put("url",page.url());r.put("title",page.title());r.put("pageText",first(page,"body"));
        r.put("elements",page.locator("a[href],button,input,h1,h2,[class*=job],[class*=position],[class*=user],[class*=login],[class*=salary],[class*=company]").evaluateAll("els=>els.filter(e=>e.getClientRects().length).slice(0,160).map(e=>({tag:e.tagName,cls:e.className,text:e.innerText?.slice(0,900),placeholder:e.getAttribute('placeholder'),href:e.getAttribute('href'),attrs:[...e.attributes].filter(a=>/^(data-|role|type|name)/.test(a.name)).map(a=>({name:a.name,value:a.value}))}))"));
        r.put("headerParts",page.locator(".jobtitle *").evaluateAll("els=>els.filter(e=>e.getClientRects().length).slice(0,45).map(e=>({tag:e.tagName,cls:e.className,text:e.innerText?.slice(0,400)}))"));
        r.put("structure",page.locator(".other,.search-top-input,.detail-content-common,.detail-right").evaluateAll("els=>els.filter(e=>e.getClientRects().length).slice(0,8).map(e=>({cls:e.className,html:e.outerHTML.slice(0,16000)}))"));
        r.put("filterMetrics",page.locator(".other,.mutiselect,.mutiselect-input,.val").evaluateAll("els=>els.slice(0,60).map(e=>({cls:e.className,text:e.innerText?.slice(0,80),display:getComputedStyle(e).display,visibility:getComputedStyle(e).visibility,rects:[...e.getClientRects()].map(r=>({width:r.width,height:r.height})),parent:e.parentElement?.className}))"));
        r.put("searchControls",page.locator("input,[contenteditable],[class*=search],[class*=filter],[class*=intention]").evaluateAll("els=>els.filter(e=>e.getClientRects().length).slice(0,65).map(e=>({tag:e.tagName,cls:e.className,text:e.innerText?.slice(0,1200),placeholder:e.getAttribute('placeholder'),children:[...e.children].slice(0,8).map(n=>({tag:n.tagName,cls:n.className,text:n.innerText?.slice(0,100)}))}))"));
        r.put("loggedIn",loggedIn(page));r.put("message",loggedIn(page)?"已识别应届生登录态":"请在独立Chrome核对应届生登录态");return r;
    }
    @Override public boolean loggedIn(Page p){String name=first(p,".userinfo");return !name.isBlank()&&!name.matches(".*(?:登录|注册).*");}
    private static class Attention extends IllegalStateException {Attention(String message){super(message);}}
    @Override void check(Page p){String body=first(p,"body");if(body.matches("(?is).*(滑动验证页面|请完成安全验证|请按住滑块|访问被拒绝|403 Forbidden).*"))throw new Attention("应届生要求登录或安全验证，请在该平台独立Chrome处理");if(!"q.yingjiesheng.com".equals(URI.create(p.url().split("[?#]",2)[0]).getHost()))throw new Attention("应届生跳转到其他站点，未读取或投递");
        if(InternshipPolicy.dailyCommunicationLimit(body)){store.platformQuotaReached("job51",java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString(),"应届生提示今日投递上限");throw new Attention("应届生今日投递上限，今天停止该平台");}}
    void internshipOnly(Page p){
        try{p.locator(".mutiselect-input:visible .val").first().waitFor(new Locator.WaitForOptions().setTimeout(12000));}catch(PlaywrightException e){throw new IllegalStateException("应届生职位类型筛选未加载完成，未采集",e);}
        var inputs=p.locator(".mutiselect-input").all().stream().filter(l->l.isVisible()&&l.locator(".val").innerText().trim().matches("职位类型|实习")).toList();
        if(inputs.size()!=1)throw new IllegalStateException("应届生可见职位类型筛选入口不唯一，未采集");Locator filter=inputs.getFirst().locator("..");
        if(!filter.locator(".mutiselect-input .val").innerText().trim().equals("实习")){
            Locator option=filter.locator(".mutiselect-select-item .text[title='实习']");filter.locator(".mutiselect-input").hover();
            try{option.waitFor(new Locator.WaitForOptions().setTimeout(1200));}catch(TimeoutError e){filter.locator(".mutiselect-input").click();}
            try{option.waitFor(new Locator.WaitForOptions().setTimeout(4000));option.click(new Locator.ClickOptions().setTimeout(5000));}catch(PlaywrightException e){if(!filter.locator(".mutiselect-input .val").innerText().trim().equals("实习"))throw new IllegalStateException("应届生实习筛选选项未展开，未采集",e);}
            for(int n=0;n<30&&!filter.locator(".mutiselect-input .val").innerText().trim().equals("实习");n++)p.waitForTimeout(200);
            p.locator(".userinfo").hover();p.waitForTimeout(700);
        }
        if(!filter.locator(".mutiselect-input .val").innerText().trim().equals("实习"))throw new IllegalStateException("未确认应届生已选择实习，未采集");
    }
    private boolean empty(Page p){return first(p,"body").matches("(?s).*(暂无相关职位|暂无职位|暂无符合条件的职位|没有找到相关).*" );}
    private void awaitListings(Page p){for(int n=0;n<60&&p.locator("a.search-list-href").count()==0&&!empty(p);n++)p.waitForTimeout(200);check(p);}
    void search(Page p,String word){
        p.locator(".search-top-input:visible #keywordInput").first().waitFor(new Locator.WaitForOptions().setTimeout(12000));
        var forms=p.locator(".search-top-input:visible").all().stream().filter(f->f.locator("#keywordInput:visible").count()==1&&f.locator(".search-button:visible").count()==1).toList();
        if(forms.isEmpty())throw new IllegalStateException("应届生搜索栏未完整加载，未采集");
        Locator form=forms.getFirst();form.locator("#keywordInput:visible").fill(word);form.locator(".search-button:visible").click();p.waitForTimeout(1000);check(p);
        // Search renders an additional sticky form. Validate the keyword without reusing a now-ambiguous locator.
        boolean confirmed=p.locator(".search-top-input:visible #keywordInput:visible").all().stream().anyMatch(input->input.inputValue().equals(word));
        if(!confirmed)throw new IllegalStateException("未确认应届生关键词已输入，未采集");
    }
    @Override public JSONObject readJob(Page p,String url){
        String identity=jobUrl(url);check(p);if(identity.isBlank()||!identity.equals(jobUrl(p.url())))throw new IllegalStateException("应届生岗位身份或跳转发生变化，未采集");
        p.locator(".jobtitle .job").waitFor(new Locator.WaitForOptions().setTimeout(12000));p.locator(".jobinfo .text").waitFor(new Locator.WaitForOptions().setTimeout(12000));
        String title=first(p,".jobtitle .job"),salary=first(p,".jobtitle .salary"),jd=first(p,".jobinfo .text");
        List<String> parts=p.locator(".detail-title-left-center > .item").allTextContents().stream().map(String::strip).map(v->v.replace('\u00a0',' ').trim()).toList();
        Locator companySection=p.locator(".detail-content-common").filter(new Locator.FilterOptions().setHas(p.locator(".morecoinfo")));
        String company=companySection.count()==1?companySection.locator(".text").innerText().split("[，,]公司规模为",2)[0].trim():"";
        if(title.isBlank()||company.isBlank()||jd.length()<20||parts.size()<4)throw new IllegalStateException("应届生岗位详情未完整读取，已跳过");
        return new JSONObject().put("platform","job51").put("siteAdapter",ADAPTER).put("url",identity).put("navigationUrl",identity).put("title",title).put("company",company).put("salary",salary).put("city",parts.get(0)).put("experience",parts.get(1)).put("degree",parts.get(2)).put("employmentType",parts.get(3)).put("jd",jd).put("deliveryMethod","native-resume");
    }
    @Override public void collect(JSONObject c,Page p,BooleanSupplier stopped,Consumer<JSONObject> accept,Consumer<String> progress){
        Set<String> seen=new HashSet<>();int count=0,failures=0;boolean sawListing=false;
        List<String> words=c.getString("source").equals("recommend")?List.of(""):c.getJSONArray("keywords").toList().stream().map(Object::toString).toList();
        for(String word:words){
            if(stopped.getAsBoolean())return;p.navigate(word.isEmpty()?Platforms.home("job51"):SEARCH);check(p);
            for(int n=0;n<30&&!loggedIn(p);n++)p.waitForTimeout(200);if(!loggedIn(p))throw new Attention("应届生需要先核对登录态，未将公共岗位冒充账号推荐");
            if(!word.isEmpty()){
                search(p,word);
            }
            awaitListings(p);internshipOnly(p);
            for(int page=0;page<c.getInt("refreshRounds")&&!stopped.getAsBoolean();page++){
                awaitListings(p);List<Locator> cards=p.locator("a.search-list-href").all();sawListing|=!cards.isEmpty()||empty(p);
                for(Locator card:cards){
                    if(stopped.getAsBoolean()||count>=c.getInt("maxScan"))return;if(!card.isVisible())continue;String url=jobUrl(card.getAttribute("href"));if(url.isBlank()||!seen.add(url))continue;
                    String text=card.innerText(),title=text.split("\\R",2)[0].trim(),salary=firstIn(card,".right-salary");
                    if(!InternshipPolicy.formalReason(new JSONObject().put("title",title).put("salary",salary).put("cardText",text)).isBlank()){store.excludeFormalListing();continue;}
                    if(store.blocksRetry(store.get(InternshipPolicy.hash(url))))continue;
                    Page detail=p.context().newPage();detail.setDefaultTimeout(8000);detail.setDefaultNavigationTimeout(25000);boolean attention=false;JSONObject job;
                    try{
                        detail.navigate(url,new Page.NavigateOptions().setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED));job=readJob(detail,url);
                        if(!job.getString("title").equals(title))throw new IllegalStateException("应届生详情名称与列表岗位不一致，已跳过");
                        if(!job.getString("employmentType").equals("实习")||!InternshipPolicy.formalReason(job).isBlank()){store.excludeFormalListing();continue;}
                    }catch(Attention e){attention=true;detail.bringToFront();store.recordReadFailure(url,e.getMessage());throw e;}
                    catch(PlaywrightException|IllegalStateException e){failures++;store.recordReadFailure(url,e instanceof PlaywrightException?"应届生详情加载或字段读取超时":e.getMessage());continue;}
                    finally{if(!attention)detail.close();}
                    job.put("source",c.getString("source")).put("sourceKeyword",word);progress.accept("应届生实习：读取并评分第 "+(++count)+" 个候选 · "+title);accept.accept(job);
                    for(int n=0;n<c.getInt("intervalSeconds")*5&&!stopped.getAsBoolean()&&count<c.getInt("maxScan");n++)p.waitForTimeout(200);
                }
                Locator next=p.locator("button.btn-next:visible");if(page+1>=c.getInt("refreshRounds")||next.count()!=1||next.isDisabled())break;
                String before=cards.isEmpty()?"":cards.getFirst().getAttribute("href");next.click();for(int n=0;n<30&&p.locator("a.search-list-href").count()>0&&Objects.equals(before,p.locator("a.search-list-href").first().getAttribute("href"));n++)p.waitForTimeout(200);
                if(p.locator("a.search-list-href").count()>0&&Objects.equals(before,p.locator("a.search-list-href").first().getAttribute("href")))throw new IllegalStateException("应届生翻页后未确认列表更新，未重复读取");
            }
        }
        if(!stopped.getAsBoolean()&&!sawListing)throw new IllegalStateException("应届生未读取到真实实习列表，请核对登录或页面入口");
        if(!stopped.getAsBoolean()&&count==0&&failures>0)throw new IllegalStateException("应届生有"+failures+"条详情读取失败，未获得完整候选");
    }
    private static String firstIn(Locator card,String selector){Locator value=card.locator(selector);return value.count()==1?value.innerText().trim():"";}
    static String jobUrl(String url){try{URI u=URI.create(url.split("[?#]",2)[0]);return "https".equals(u.getScheme())&&"q.yingjiesheng.com".equals(u.getHost())&&u.getUserInfo()==null&&u.getPort()==-1&&u.getPath().matches("/jobdetail/\\d+\\.html")?"https://q.yingjiesheng.com"+u.getPath():"";}catch(Exception e){return "";}}
    @Override public Object inspectDetail(Page p,String url){String target=jobUrl(url);if(target.isBlank()&&!Set.of(Platforms.home("job51"),"https://q.yingjiesheng.com/pc/searchintern","https://q.yingjiesheng.com/jobs/search/?pageCode=internship|search|jobsearchlb").contains(url))throw new IllegalArgumentException("只允许应届生实习入口和真实岗位详情");Page detail=p.context().newPage();try{detail.navigate(target.isBlank()?url:target);detail.waitForTimeout(1500);return diagnostic(detail);}finally{detail.close();}}
    boolean submitted(Page p){return p.locator(".detail-title-right .delivery-btn:visible").all().stream().anyMatch(l->l.innerText().trim().matches("已申请|已投递|申请成功|投递成功"));}
    @Override public boolean deliver(JSONObject c,String id,JSONObject entry,Page p,BooleanSupplier stopped){
        if(!configuration.get().optBoolean("resumeReady"))throw new IllegalStateException("应届生站内简历尚未核对，未提交");
        JSONObject job=entry.getJSONObject("job");String url=jobUrl(job.optString("url"));
        if(!job.optString("siteAdapter").equals(ADAPTER)||url.isBlank())throw new IllegalStateException("不是当前应届生已审核岗位，未提交");
        p.navigate(url);check(p);for(int n=0;n<30&&!loggedIn(p);n++)p.waitForTimeout(200);
        if(!loggedIn(p)||!same(job,readJob(p,url)))throw new IllegalStateException("应届生登录或岗位信息变化，未提交");
        if(submitted(p)){entry.put("status","complete").put("nativeResumeConfirmed",true).put("alreadySubmittedOnPlatform",true);store.save(id,entry);return false;}
        var buttons=p.locator(".detail-title-right .delivery-btn:visible").all().stream().filter(l->l.innerText().trim().equals("立即申请")).toList();
        if(buttons.size()!=1)throw new IllegalStateException("应届生岗位申请入口不唯一，未提交");
        if(stopped.getAsBoolean())return false;
        entry.put("status","native_attempted").put("nativeAttemptAt",java.time.Instant.now().toString());store.save(id,entry);buttons.getFirst().click();
        for(int n=0;n<60;n++){
            check(p);if(!url.equals(jobUrl(p.url())))throw new IllegalStateException("应届生申请转往其他页面，未确认投递成功，未重新申请");
            if(submitted(p)){
                Page receipt=p.context().newPage();
                try{receipt.navigate(url);if(!loggedIn(receipt)||!same(job,readJob(receipt,url))||!submitted(receipt))throw new IllegalStateException("应届生申请状态未在重新打开岗位后保持，未确认投递成功");}
                finally{receipt.close();}
                entry.put("status","complete").put("nativeResumeConfirmed",true).put("nativeReceiptSource","yingjiesheng-job-applied-state").put("nativeReceiptAt",java.time.Instant.now().toString());store.save(id,entry);
                store.updateSchedule(s->s.put("nativeAcceptance",new JSONObject().put("adapter",ADAPTER).put("liveSubmissionVerified",true).put("automationVerified",true).put("recordId",id).put("at",java.time.Instant.now().toString()).put("source","yingjiesheng-job-applied-state")));
                return true;
            }
            if(stopped.getAsBoolean())break;p.waitForTimeout(300);
        }
        entry.put("lastDeliveryIssue","应届生未确认投递成功，请核对原申请窗口；不会重复申请");store.save(id,entry);throw new IllegalStateException(entry.getString("lastDeliveryIssue"));
    }
}
