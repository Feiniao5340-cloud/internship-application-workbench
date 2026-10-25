package com.getjobs.worker.boss;

import com.getjobs.worker.parallel.Platforms;
import com.microsoft.playwright.*;
import com.getjobs.worker.manager.BackgroundBrowserPages;
import org.json.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.function.*;

/** DOM-only native resume adapters. No CAPTCHA solving, private API or bulk apply. */
public class InternshipNativeSites {
    static final String ZHILIAN_CAMPUS_RECOMMEND="https://xiaoyuan.zhaopin.com/recommend?refcode=4032";
    private static class AttentionRequired extends IllegalStateException {AttentionRequired(String reason){super(reason);}}
    private final InternshipStore store;
    private final String platform;
    private final Supplier<JSONObject> configuration;
    private String legacyHome(){return platform.equals("job51")?"https://www.51job.com/":Platforms.home(platform);}
    public InternshipNativeSites(InternshipStore store,String platform,Supplier<JSONObject> configuration) {
        this.store=store;this.platform=Platforms.require(platform);this.configuration=configuration;
    }
    public static String canonical(String platform,String value) {
        try {
            URI uri=URI.create(value);String host=uri.getHost();String domain=switch(platform){case "zhilian"->"zhaopin.com";case "job51"->"51job.com";case "lagou"->"lagou.com";default->"";};
            if(domain.isEmpty()||!"https".equals(uri.getScheme())||host==null||!(host.equals(domain)||host.endsWith("."+domain))||uri.getUserInfo()!=null||uri.getPort()!=-1)return "";
            String path=uri.getPath();
            if(platform.equals("zhilian")) {
                if(host.equals("xiaoyuan.zhaopin.com")&&path.matches("/job/[A-Za-z0-9_-]+"))return "https://www.zhaopin.com/jobdetail/"+path.substring(5)+".htm";
                if(host.equals("jobs.zhaopin.com")&&path.matches("/[A-Za-z0-9_-]+\\.html?"))return "https://www.zhaopin.com/jobdetail"+path.replaceFirst("\\.html$",".htm");
                if(host.equals("www.zhaopin.com")&&path.matches("/jobdetail/[A-Za-z0-9_-]+\\.html?"))return "https://www.zhaopin.com"+path.replaceFirst("\\.html$",".htm");
            }
            if(platform.equals("job51")) {
                if(host.equals("jobs.51job.com")&&path.matches("/.+/\\d+\\.html"))return "https://"+host+path;
                if(host.equals("we.51job.com")&&path.equals("/pc/jobdetail")&&uri.getQuery()!=null)for(String pair:uri.getQuery().split("&"))if(pair.matches("jobId=\\d+"))return "https://we.51job.com/pc/jobdetail?"+pair;
            }
            if(platform.equals("lagou")&&path.matches("/(?:wn/)?jobs/\\d+\\.html"))return "https://"+host+path;
        }catch(Exception ignored){}return "";
    }
    static String navigationUrl(String platform,String value){String identity=canonical(platform,value);if(identity.isBlank())return "";URI u=URI.create(value);return platform.equals("zhilian")&&u.getHost().equals("xiaoyuan.zhaopin.com")?"https://xiaoyuan.zhaopin.com"+u.getPath():identity;}
    static String job51PartnerJobUrl(String value) {
        try {
            URI u=URI.create(value);if(!"https".equals(u.getScheme())||!"q.yingjiesheng.com".equals(u.getHost())||u.getUserInfo()!=null||u.getPort()!=-1)return "";
            String id="";if(u.getPath().matches("/jobdetail/\\d+\\.html"))id=u.getPath().replaceFirst("/jobdetail/(\\d+)\\.html","$1");
            else if(!u.getPath().equals("/pc/jobdetail"))return "";
            if(u.getQuery()!=null)for(String pair:u.getQuery().split("&"))if(pair.startsWith("jobid=")) {
                String queryId=pair.substring(6);if(!queryId.matches("\\d+")||(!id.isEmpty()&&!id.equals(queryId)))return "";id=queryId;
            }
            return id.isEmpty()?"":"https://jobs.51job.com/all/"+id+".html";
        }catch(Exception e){return "";}
    }
    static String first(Page p,String selectors) {
        for(String selector:selectors.split(";"))for(Locator l:p.locator(selector).all())if(l.isVisible()){String text=l.innerText().trim();if(!text.isBlank())return text;}
        return "";
    }
    private String titleSelectors(){return switch(platform){case "zhilian"->"h1;.summary-plane__title;.job-name";case "job51"->"h1;.job-name;.jname";default->".job-name h1;.position-head h1;h1";};}
    private String salarySelectors(){return switch(platform){case "zhilian"->".job-banner__salary;.summary-planes__salary;.summary-plane__salary;.salary;.job-salary";case "job51"->".salary;.job-salary;.tHeader .cn strong";default->".salary;.job-salary;.position-head .salary";};}
    private String companySelectors(){return switch(platform){case "zhilian"->".company-info__name;.company-name;.company__title;.company-name a;.companyinfo__name";case "job51"->".company-name;.cname a;.company-info .name";default->".company-name;.job-company h2;.company";};}
    private String citySelectors(){return switch(platform){case "zhilian"->".job-banner__desc-text:first-child;.summary-planes__info .workCity-link;.summary-plane__info .city;.job-city;.city";case "job51"->".tHeader .cn .type_2;.job-city;.city;.job-area";default->".job-city;.work_addr a;.city";};}
    private String jdSelectors(){return switch(platform){case "zhilian"->".job-info__desc;.describtion-card__detail-content;.describtion__detail-content;.job-description;.job-summary";case "job51"->".job_msg;.job-description;.job-describe;.job-detail";default->".job_bt;.job-description;.job-detail";};}
    void check(Page p) {
        String body=first(p,"body");
        if(body.matches("(?is).*(403 Forbidden|访问被拒绝|访问受限|访问验证|请完成验证|安全验证|滑动验证|请按住滑块|验证后继续).*"))throw new AttentionRequired(Platforms.label(platform)+"要求登录或安全验证，请在该平台独立浏览器处理");
        if(InternshipPolicy.dailyCommunicationLimit(body)) {
            store.platformQuotaReached(platform,LocalDate.now(ZoneId.of("Asia/Shanghai")).toString(),"平台提示今日投递上限");
            throw new AttentionRequired("平台提示今日投递上限，今天停止该平台");
        }
    }
    public boolean loggedIn(Page p) {
        String selectors=switch(platform){case "zhilian"->".page-header__user__logged-in;.home-header__c-login .c-login__top__name;.header-nav__c-login .c-login__top__name;header .user-name;header .user-name__name;.user-menu .user-name;.nav-user .username";case "job51"->".user-container .user-header;a.uname;.header .uname;.user-name;.user-info .name;.user-info.el-popover__reference";default->"#lg_tbar .unick;.user_dropdown .username;header .user-name;.user-info .name";};
        String name=first(p,selectors);return !name.isBlank()&&!name.matches(".*(登录|注册).*" );
    }
    public Map<String,Object> checkSession(Page p) {
        p.navigate(platform.equals("zhilian")?ZHILIAN_CAMPUS_RECOMMEND:legacyHome());
        try{check(p);}catch(IllegalStateException e){return Map.of("loggedIn",false,"requiresUserAction",true,"message",e.getMessage(),"persistent",true);}
        for(int i=0;i<20&&!loggedIn(p);i++)p.waitForTimeout(200);
        return Map.of("loggedIn",loggedIn(p),"message",loggedIn(p)?"已识别该平台登录界面":"请在该平台独立浏览器手动登录，再检查登录态","persistent",true);
    }
    public JSONObject readJob(Page p,String url) {
        String canonical=canonical(platform,url);if(canonical.isBlank())throw new IllegalArgumentException("岗位网址不属于当前平台");check(p);
        if(!canonical.equals(canonical(platform,p.url()))) {
            if(p.url().matches("(?i).*(login|passport|account|security|verify).*"))throw new AttentionRequired("网站要求重新登录或验证，未读取或投递岗位");
            throw new IllegalStateException("网站已跳转到其他岗位或页面，未投递");
        }
        p.locator(jdSelectors().replace(';',',')).first().waitFor(new Locator.WaitForOptions().setTimeout(8000));
        String title=first(p,titleSelectors()),company=first(p,companySelectors()),salary=first(p,salarySelectors()),city=first(p,citySelectors()),jd=first(p,jdSelectors());
        String employmentType="";
        if(platform.equals("zhilian")&&p.locator(".job-banner__name").count()>0){
            title=(String)p.locator(".job-banner__name").first().evaluate("el=>[...el.childNodes].filter(n=>n.nodeType===Node.TEXT_NODE).map(n=>n.textContent).join('').trim()");
            List<String> info=p.locator(".job-banner__desc-text").allTextContents();if(info.size()>=3)employmentType=info.get(2).trim();
            String features=first(p,".job-info__feature");if(!features.isBlank())jd=features+"\n"+jd;
        }
        if(platform.equals("job51")&&company.isBlank()) {
            String[] metadata=p.title().split("\\s*\\|\\s*");
            String metadataTitle=title.replaceFirst("\\s*[(（]职位编号[：:][^()（）]+[)）]\\s*$","");
            if(metadata.length==5&&metadata[4].equals("前程无忧")&&metadata[0].equals(metadataTitle+"招聘")&&metadata[2].equals(salary))company=metadata[3];
        }
        if(title.isBlank()||company.isBlank()||jd.length()<20)throw new IllegalStateException("岗位详情未完整读取，可能需要登录或页面已变化；未投递");
        String experience=switch(platform){case "job51"->first(p,".tHeader .cn .type_3");case "zhilian"->first(p,".summary-planes__info li:nth-child(2)");default->"";};
        return new JSONObject().put("platform",platform).put("url",canonical).put("navigationUrl",navigationUrl(platform,p.url())).put("title",title).put("company",company).put("salary",salary).put("city",city).put("experience",experience).put("employmentType",employmentType).put("jd",jd).put("deliveryMethod","native-resume");
    }
    public List<String> listingUrls(JSONObject c) {
        if(c.getString("source").equals("recommend"))return List.of(platform.equals("zhilian")?ZHILIAN_CAMPUS_RECOMMEND:legacyHome());
        List<String> urls=new ArrayList<>();for(Object k:c.getJSONArray("keywords")) {
            String word=URLEncoder.encode(k.toString(),StandardCharsets.UTF_8);
            urls.add(switch(platform){case "zhilian"->"https://www.zhaopin.com/sou/?kw="+word;case "job51"->"https://we.51job.com/pc/search?keyword="+word;default->"https://www.lagou.com/wn/jobs?kd="+word;});
        }return urls;
    }
    List<String> jobUrls(Page p) {
        Set<String> urls=new LinkedHashSet<>();for(Object raw:(List<?>)p.locator("a[href]").evaluateAll("els=>els.map(e=>e.href)")) {String url=canonical(platform,raw.toString());if(!url.isBlank())urls.add(url);}return new ArrayList<>(urls);
    }
    private void selectCampusInternship(Page p){
        p.locator(".app-select__slot").first().waitFor(new Locator.WaitForOptions().setTimeout(8000));
        Locator filter=p.locator(".app-select").filter(new Locator.FilterOptions().setHas(p.locator(".app-select__slot").filter(new Locator.FilterOptions().setHasText(java.util.regex.Pattern.compile("^(工作性质|实习)$")))));
        if(filter.count()!=1)throw new IllegalStateException("校园推荐的工作性质筛选入口不唯一，未采集");
        if(filter.locator(".app-select__slot").innerText().trim().equals("实习"))return;
        filter.locator(".app-select__slot").hover();
        filter.locator(".app-select__list").getByText("实习",new Locator.GetByTextOptions().setExact(true)).click();
        for(int n=0;n<20&&!filter.locator(".app-select__slot").innerText().trim().equals("实习");n++)p.waitForTimeout(200);
        if(!filter.locator(".app-select__slot").innerText().trim().equals("实习"))throw new IllegalStateException("未确认校园推荐已选择实习，未采集");
        p.waitForTimeout(1000);for(int n=0;n<25&&!p.locator(".position-card__tags__item").allTextContents().stream().anyMatch(t->t.trim().equals("实习"));n++)p.waitForTimeout(200);check(p);
    }
    private Page nextCampusCard(Page p,Set<String> visited,BooleanSupplier stopped,Consumer<String> progress){
        for(Locator card:p.locator(".position-card").all()){
            if(stopped.getAsBoolean())return null;if(!card.isVisible())continue;
            String signature=card.innerText();if(!visited.add(signature))continue;
            List<String> tags=card.locator(".position-card__tags__item").allTextContents();if(tags.stream().noneMatch(t->t.trim().equals("实习")))continue;
            String title=card.locator(".position-card__job-name").innerText().trim(),salary=card.locator(".position-card__salary").innerText().trim();
            if(!InternshipPolicy.formalReason(new JSONObject().put("title",title).put("salary",salary).put("experience",String.join("\n",tags)).put("jd","")).isBlank()){store.excludeFormalListing();continue;}
            Page opened=null;boolean retained=false;
            try{
                opened=p.waitForPopup(new Page.WaitForPopupOptions().setTimeout(8000),()->card.locator(".position-card__job-name").click());
                for(int n=0;n<30&&!stopped.getAsBoolean()&&canonical(platform,opened.url()).isBlank();n++)p.waitForTimeout(200);
                if(!canonical(platform,opened.url()).isBlank()&&!stopped.getAsBoolean()){retained=true;return opened;}
                if(!stopped.getAsBoolean()){check(opened);throw new AttentionRequired("校园岗位详情未到达已核对的岗位页面，请检查登录或跳转入口");}
            }catch(AttentionRequired e){throw e;}
            catch(PlaywrightException e){if(!stopped.getAsBoolean()){store.recordReadFailure(p.url()+"#card-"+InternshipPolicy.hash(signature),"打开校园岗位详情超时，已跳过");progress.accept("智联校园：岗位详情打开异常，继续下一条");}}
            finally{if(opened!=null&&!retained)opened.close();}
        }return null;
    }
    private String selectedZhilianUrl(Page p){
        var links=p.locator("a[href]").filter(new Locator.FilterOptions().setHasText(java.util.regex.Pattern.compile("^查看更多职位信息$"))).all().stream().filter(Locator::isVisible).toList();
        if(links.size()!=1)return "";String href=links.getFirst().getAttribute("href");return href==null?"":canonical(platform,URI.create(p.url()).resolve(href).toString());
    }
    private Page nextJob51Card(Page p,Set<String> visited,BooleanSupplier stopped,Consumer<String> progress) {
        for(Locator card:p.locator(".joblist .joblist-item").all()) {
            if(stopped.getAsBoolean())return null;if(!card.isVisible())continue;String signature=card.innerText();if(!visited.add(signature))continue;
            String title=card.locator(".joblist-item-jobname .job-info").innerText().trim(),salary=card.locator(".joblist-item-jobinfo .sal").innerText().trim();
            if(!InternshipPolicy.formalReason(new JSONObject().put("title",title).put("salary",salary).put("jd","")).isBlank()){store.excludeFormalListing();continue;}
            Page opened=null;boolean retained=false;String stage="等待岗位新标签页";
            // Campus links use noopener; observe only this platform's isolated browser context.
            try{opened=p.context().waitForPage(new BrowserContext.WaitForPageOptions().setTimeout(8000),()->card.locator(".joblist-item-jobname .job-info").click());stage="等待详情地址";
                // window.open can expose about:blank before the site's asynchronous navigation.
                for(int n=0;n<40&&!stopped.getAsBoolean()&&canonical(platform,opened.url()).isBlank()&&job51PartnerJobUrl(opened.url()).isBlank();n++) {
                    check(opened);opened.waitForTimeout(200);
                }
                String partner=job51PartnerJobUrl(opened.url());
                if(!partner.isBlank()) {
                    stage="读取同ID前程详情";
                    opened.navigate(partner,new Page.NavigateOptions().setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED));JSONObject actual=readJob(opened,partner);
                    if(!InternshipPolicy.evidenceText(actual.getString("title")).equals(InternshipPolicy.evidenceText(title))||!InternshipPolicy.evidenceText(actual.getString("salary")).equals(InternshipPolicy.evidenceText(salary))) {
                        store.recordReadFailure(partner,"校园跳转返回前程详情后岗位名称或薪资与搜索卡片不一致，已跳过");continue;
                    }
                }
                if(!canonical(platform,opened.url()).isBlank()){stage="等待前程详情正文";opened.waitForLoadState(com.microsoft.playwright.options.LoadState.DOMCONTENTLOADED);retained=true;return opened;}
                check(opened);URI destination=URI.create(opened.url());String location=destination.getHost()==null?destination.getScheme():destination.getHost()+destination.getPath();
                store.recordReadFailure(p.url()+"#card-"+InternshipPolicy.hash(signature),"岗位名称未打开可核对的详情链接，未读取其他推荐职位；跳转位置："+location);
            }catch(AttentionRequired e){if(opened!=null){retained=true;}throw e;}
            catch(PlaywrightException e){if(!stopped.getAsBoolean()){String location="尚未取得新标签页";if(opened!=null)try{URI u=URI.create(opened.url());location=u.getHost()==null?u.getScheme():u.getHost()+u.getPath();}catch(Exception ignored){}store.recordReadFailure(p.url()+"#card-"+InternshipPolicy.hash(signature),"岗位名称打开详情超时，已跳过；阶段："+stage+"；位置："+location);progress.accept("前程无忧：岗位详情打开异常，继续下一条");}}
            finally{if(opened!=null&&!retained)opened.close();}
        }return null;
    }
    private List<String> nextZhilianCard(Page p,Set<String> visited,BooleanSupplier stopped,Consumer<String> progress){
        for(Locator card:p.locator(".job-list-panel > .job-card").all()){
            if(stopped.getAsBoolean())return List.of();if(!card.isVisible())continue;
            String signature=card.innerText();if(!visited.add(signature))continue;
            String title=card.locator(".job-card__title-main").innerText().trim(),salary=card.locator(".job-card__salary").innerText().trim();
            String experience=String.join("\n",card.locator(".job-card__skill-tag").allTextContents());
            if(!InternshipPolicy.formalReason(new JSONObject().put("title",title).put("salary",salary).put("experience",experience).put("jd","")).isBlank()){
                store.excludeFormalListing();progress.accept("智联：已在列表预筛排除正式岗位，继续读取实习候选");continue;
            }
            String before=selectedZhilianUrl(p);boolean active=Optional.ofNullable(card.getAttribute("class")).orElse("").contains("job-card--active");
            try{
                card.locator(".job-card__title-main").click();
                for(int i=0;i<30&&!stopped.getAsBoolean();i++){
                    check(p);String selected=selectedZhilianUrl(p);
                    if(!selected.isBlank()&&(active||!selected.equals(before)))return List.of(selected);
                    p.waitForTimeout(200);
                }
            }catch(AttentionRequired e){throw e;}catch(PlaywrightException ignored){if(stopped.getAsBoolean())return List.of();}
            if(!stopped.getAsBoolean())store.recordReadFailure(p.url()+"#card-"+InternshipPolicy.hash(signature),"选择岗位后未确认该岗位详情链接，已跳过");
        }return List.of();
    }
    private void scrollListing(Page p){
        if(platform.equals("zhilian")&&p.locator(".job-list-panel > .job-card").count()>0)p.locator(".job-list-panel > .job-card").first().evaluate("el=>{for(let n=el.parentElement;n;n=n.parentElement)if(n.scrollHeight>n.clientHeight+10&&/auto|scroll/.test(getComputedStyle(n).overflowY)){n.scrollBy(0,800);return}window.scrollBy(0,800)}");
        else p.evaluate("window.scrollBy(0,800)");
    }
    public void collect(JSONObject c,Page list,BooleanSupplier stopped,Consumer<JSONObject> accept,Consumer<String> progress) {
        Set<String> seen=new HashSet<>(),visitedCards=new HashSet<>();int count=0,failed=0;boolean sawListing=false;String lastFailure="";
        for(String url:listingUrls(c))for(int refresh=0;refresh<c.getInt("refreshRounds")&&!stopped.getAsBoolean();refresh++) {
            if(count>=c.getInt("maxScan"))return;list.navigate(url);check(list);
            boolean campus=platform.equals("zhilian")&&URI.create(list.url()).getHost().equals("xiaoyuan.zhaopin.com");
            boolean job51Search=platform.equals("job51")&&c.getString("source").equals("search");
            if(job51Search)for(int w=0;w<30&&list.locator(".joblist .joblist-item").count()==0&&!stopped.getAsBoolean();w++)list.waitForTimeout(250);
            if(campus){for(int n=0;n<20&&!stopped.getAsBoolean()&&!loggedIn(list);n++)list.waitForTimeout(200);}
            if(c.getString("source").equals("recommend")&&!loggedIn(list))throw new AttentionRequired("首页推荐需要先核对当前账号登录态，未把公共热招职位冒充账号推荐");
            if(campus)selectCampusInternship(list);
            for(int w=0;w<30&&jobUrls(list).isEmpty()&&(!platform.equals("zhilian")||list.locator(".job-list-panel > .job-card,.position-card").count()==0)&&!stopped.getAsBoolean();w++)list.waitForTimeout(250);
            if(platform.equals("zhilian")&&first(list,".job-list-empty__title").equals("暂无求职意向，添加后推荐专属职位"))throw new AttentionRequired("智联尚未设置站内求职意向，请在智联添加后再运行推荐轮次");
            int stable=0;while(stable<3&&!stopped.getAsBoolean()&&count<c.getInt("maxScan")) {
                boolean found=false;boolean cards=platform.equals("zhilian")&&list.locator(".job-list-panel > .job-card").count()>0;
                int failuresBefore=store.roundSummary().optInt("readFailed");
                Page campusDetail=campus?nextCampusCard(list,visitedCards,stopped,progress):job51Search?nextJob51Card(list,visitedCards,stopped,progress):null;
                List<String> urls=campus||job51Search?(campusDetail==null?List.of():List.of(navigationUrl(platform,campusDetail.url()))):cards?nextZhilianCard(list,visitedCards,stopped,progress):jobUrls(list);if(cards||campus&&list.locator(".position-card").count()>0||job51Search&&list.locator(".joblist .joblist-item").count()>0||!urls.isEmpty())sawListing=true;
                int cardFailures=store.roundSummary().optInt("readFailed")-failuresBefore;if(cardFailures>0){failed+=cardFailures;lastFailure=store.roundSummary().getJSONObject("lastReadFailure").optString("reason");}
                for(String detailUrl:urls) {
                    if(stopped.getAsBoolean()||count>=c.getInt("maxScan")){if(campusDetail!=null)campusDetail.close();return;}
                    if(!seen.add(canonical(platform,detailUrl))||store.blocksRetry(store.get(InternshipPolicy.hash(canonical(platform,detailUrl))))){if(campusDetail!=null)campusDetail.close();continue;}found=true;
                    Page detail=campusDetail==null?BackgroundBrowserPages.create(list.context()):campusDetail;detail.setDefaultTimeout(8000);detail.setDefaultNavigationTimeout(25000);
                    JSONObject job;boolean needsAttention=false;
                    try {if(campusDetail==null)detail.navigate(detailUrl);job=readJob(detail,detailUrl);}
                    catch(AttentionRequired e){needsAttention=true;store.recordReadFailure(detailUrl,e.getMessage());throw e;}
                    catch(PlaywrightException|IllegalStateException e){
                        if(stopped.getAsBoolean())return;
                        lastFailure=e instanceof PlaywrightException?"岗位页面加载或必要字段读取超时":e.getMessage();
                        store.recordReadFailure(detailUrl,lastFailure);failed++;progress.accept(Platforms.label(platform)+"：跳过未能完整读取的岗位，继续读取后续候选");continue;
                    }finally{if(!needsAttention)detail.close();}
                    if(!InternshipPolicy.formalReason(job).isBlank()){accept.accept(job);continue;}
                    progress.accept(Platforms.label(platform)+"：读取并评分第 "+(++count)+" 个候选 · "+job.optString("title"));accept.accept(job);
                    if(count>=c.getInt("maxScan")||stopped.getAsBoolean())return;
                    for(int w=0;w<c.getInt("intervalSeconds")*5&&!stopped.getAsBoolean();w++)list.waitForTimeout(200);
                }
                stable=found?0:stable+1;scrollListing(list);list.waitForTimeout(500);check(list);
            }
        }
        if(!sawListing&&!stopped.getAsBoolean())throw new IllegalStateException((c.getString("source").equals("search")?"关键词搜索":"首页推荐")+"页面没有读取到真实岗位列表，请检查登录或页面入口");
        if(count==0&&failed>0&&!stopped.getAsBoolean())throw new IllegalStateException("本轮有 "+failed+" 条详情未能完整读取，未获得完整实习候选；最后原因："+lastFailure);
    }
    static boolean same(JSONObject a,JSONObject b) {
        for(String key:List.of("title","company","salary","city","experience","employmentType","jd"))if(!a.optString(key).replaceAll("\\s+","").equals(b.optString(key).replaceAll("\\s+","")))return false;return true;
    }
    List<Locator> applyButtons(Page p) {
        String labels=switch(platform){case "zhilian"->"立即投递|投递简历|申请职位";case "job51"->"申请职位|立即申请|立即投递";default->"投个简历|投递简历";};
        if(platform.equals("job51")){
            var primary=p.locator(".tHeader .apply-btn-new.big").all().stream().filter(l->l.isVisible()&&l.innerText().trim().matches(labels)).toList();
            if(primary.size()==1)return primary;
        }
        return p.locator("button,a,[role=button],.position-deliver,.apply-button,.apply-btn-new,.job-banner__deliver__btn").filter(new Locator.FilterOptions().setHasText(java.util.regex.Pattern.compile("^("+labels+")$"))).all().stream().filter(Locator::isVisible).toList();
    }
    boolean completed(Page p) {
        String receipt=first(p,".deliver-dialog;.successContent;.apply-success;.delivery-success;.a-job-apply-workflow;.el-message--success");
        if(java.util.regex.Pattern.compile("(?:^|\\n)\\s*(申请成功|投递成功|简历投递成功)[！!。\\s]*(?:$|\\n)").matcher(receipt).find())return true;
        String controls=switch(platform){case "zhilian"->"button.collect-and-apply__btn,.job-banner__deliver__btn,.job-header button,.summary-plane button,.apply-button";case "job51"->".apply-button,.apply-btn-new,.jobDetail-button,.job-header button";default->".position-deliver,.position-head .resume-deliver,.job-header button,.apply-button";};
        List<Locator> submitted=p.locator(controls).all().stream().filter(l->l.isVisible()&&l.innerText().trim().matches("已投递|已申请")).toList();
        return submitted.size()==1&&applyButtons(p).isEmpty();
    }
    static boolean zhilianChatUrl(String url) {
        try{URI u=URI.create(url);return "https".equals(u.getScheme())&&"i.zhaopin.com".equals(u.getHost())&&u.getUserInfo()==null&&u.getPort()==-1&&"/im".equals(u.getPath());}catch(Exception e){return false;}
    }
    static String job51Id(String url) {
        String identity=canonical("job51",url);if(identity.isBlank())return "";URI uri=URI.create(identity);
        var file=java.util.regex.Pattern.compile("/(\\d+)\\.html$").matcher(uri.getPath());if(file.find())return file.group(1);
        return uri.getQuery()!=null&&uri.getQuery().matches("jobId=\\d+")?uri.getQuery().substring(6):"";
    }
    Map<String,Object> job51HistoryReceipt(Page history,JSONObject job) {
        if(!platform.equals("job51")||!history.url().equals("https://i.51job.com/userset/my_apply.php"))return Map.of();
        String expected=job51Id(job.optString("url")),day=LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();if(expected.isBlank())return Map.of();
        List<Map<String,Object>> matches=new ArrayList<>();
        for(Locator link:history.locator("a[href]").all()) {
            if(!link.isVisible())continue;String href=(String)link.evaluate("el=>el.href");if(href==null||!job51Id(href).equals(expected))continue;
            Object raw=link.evaluate("el=>{for(let n=el.parentElement;n&&n.tagName!=='BODY';n=n.parentElement){if(n.innerText.includes('申请于')&&n.innerText.includes('申请简历'))return {text:n.innerText,links:[...n.querySelectorAll('a[href]')].map(a=>a.href)}}return null}");
            if(!(raw instanceof Map<?,?>))continue;JSONObject row=new JSONObject((Map<?,?>)raw);String text=row.optString("text"),compact=text.replaceAll("\\s+","");
            Set<String> ids=new HashSet<>();for(Object value:row.getJSONArray("links")){String id=job51Id(value.toString());if(!id.isBlank())ids.add(id);}
            var resume=java.util.regex.Pattern.compile("申请简历[：:]\\s*([^\\r\\n]+)").matcher(text);
            if(ids.size()!=1||!ids.contains(expected)||!compact.contains("申请于"+day)||text.matches("(?s).*(未申请|投递失败|申请失败|已撤回|已取消).*"))continue;
            if(!List.of("title","company","salary").stream().allMatch(k->!job.optString(k).isBlank()&&compact.contains(job.optString(k).replaceAll("\\s+","")))||!resume.find()||resume.group(1).isBlank())continue;
            matches.add(Map.of("jobId",expected,"applicationDate",day,"resumeName",resume.group(1).trim(),"rowText",text));
        }return matches.size()==1?matches.getFirst():Map.of();
    }
    private Map<String,Object> verifyJob51History(Page root,JSONObject job,BooleanSupplier stopped) {
        Page history=BackgroundBrowserPages.create(root.context());history.setDefaultNavigationTimeout(25000);
        try{history.navigate("https://i.51job.com/userset/my_apply.php");for(int n=0;n<24&&!stopped.getAsBoolean();n++){check(history);Map<String,Object> receipt=job51HistoryReceipt(history,job);if(!receipt.isEmpty())return receipt;history.waitForTimeout(250);}return Map.of();}finally{history.close();}
    }
    boolean chatIdentity(Page p,JSONObject job) {
        if(!platform.equals("zhilian")||!zhilianChatUrl(p.url()))return false;
        Locator active=p.locator(".im-session-item.is-active");if(active.count()!=1||!active.isVisible())return false;
        for(var pair:Map.of("title",".im-session-item__job","company",".im-session-item__company-name","salary",".im-session-item__salary").entrySet())
            if(active.locator(pair.getValue()).count()!=1||!active.locator(pair.getValue()).innerText().replaceAll("\\s+","").equals(job.optString(pair.getKey()).replaceAll("\\s+","")))return false;
        if(!first(p,".im-chat-header__job-title").equals(job.optString("title"))||!first(p,".im-chat-header__salary").equals(job.optString("salary"))
                ||!first(p,".im-chat-header__meta-text").equals(job.optString("company")))return false;
        return true;
    }
    boolean chatReceipt(Page p,JSONObject job) {
        if(!chatIdentity(p,job)||!first(p,".im-session-item.is-active .im-session-item__preview").equals("已发送附件简历"))return false;
        return p.locator(".im-message--custom .im-message__bubble--me").all().stream().filter(Locator::isVisible)
                .map(Locator::innerText).anyMatch(t->t.trim().matches("(?is).+\\.(pdf|docx?)")&&!t.matches("(?s).*(发送中|上传中|失败).*"));
    }
    boolean selectReceiptConversation(Page p,JSONObject job) {
        if(!platform.equals("zhilian")||!zhilianChatUrl(p.url()))return false;
        if(chatIdentity(p,job))return true;
        var selectors=Map.of("title",".im-session-item__job","company",".im-session-item__company-name","salary",".im-session-item__salary");
        var matches=p.locator(".im-session-item").all().stream().filter(Locator::isVisible).filter(row->selectors.entrySet().stream().allMatch(pair->{
            Locator value=row.locator(pair.getValue());String expected=job.optString(pair.getKey());
            return !expected.isBlank()&&value.count()==1&&value.innerText().replaceAll("\\s+","").equals(expected.replaceAll("\\s+",""));
        })).toList();
        if(matches.size()!=1)return false;
        // Select a conversation only. Never click a resume, greeting or submit action here.
        matches.getFirst().click();return true;
    }
    private void confirmNative(String id,JSONObject entry,String source,boolean automatic) {
        entry.put("status","complete").put("nativeResumeConfirmed",true).put("nativeReceiptSource",source);entry.remove("lastDeliveryIssue");store.save(id,entry);
        JSONObject proof=new JSONObject().put("liveSubmissionVerified",automatic).put("recordId",id).put("at",Instant.now().toString()).put("source",source).put("automationVerified",automatic);
        store.updateSchedule(s->{JSONObject previous=s.optJSONObject("nativeAcceptance");if(automatic||previous==null||previous.optString("recordId").equals(id))s.put("nativeAcceptance",proof);});
    }
    public Object verifyExistingReceipt(String id,JSONObject entry,Page p,String url) {
        if(platform.equals("zhilian")&&entry.optString("status").equals("native_attempted")&&!canonical(platform,url).isBlank()&&canonical(platform,url).equals(canonical(platform,entry.getJSONObject("job").optString("url")))) {
            Page receipt=BackgroundBrowserPages.create(p.context());receipt.setDefaultNavigationTimeout(25000);
            try{receipt.navigate(navigationUrl(platform,url));
                for(int n=0;n<32&&!completed(receipt);n++)receipt.waitForTimeout(250);check(receipt);
                if(!loggedIn(receipt)||!same(entry.getJSONObject("job"),readJob(receipt,url)))throw new IllegalStateException("同岗位登录身份或已投递回执未确认，继续锁定");
                if(!completed(receipt))return verifyExistingReceipt(id,entry,p,"https://i.zhaopin.com/im");
                confirmNative(id,entry,"zhilian-persisted-job-receipt",false);return Map.of("confirmed",true,"nativeResumeConfirmed",true,"automationVerified",false,"newSubmission",false);
            }finally{receipt.close();}
        }
        if(platform.equals("job51")) {
            String expected=job51Id(entry.getJSONObject("job").optString("url"));
            if(!entry.optString("status").equals("native_attempted")||!(url.equals("https://i.51job.com/userset/my_apply.php")||!expected.isBlank()&&url.equals("https://jobs.51job.com/applysuccess.php?jobid="+expected)))throw new IllegalStateException("仅核对前程已有发送尝试的申请记录，不重复投递");
            Map<String,Object> receipt=verifyJob51History(p,entry.getJSONObject("job"),()->false);if(receipt.isEmpty())throw new IllegalStateException("未找到同岗位、同公司、当日及所用简历相符的申请记录，继续锁定");
            entry.put("nativeReceiptEvidence",new JSONObject(receipt));confirmNative(id,entry,"job51-application-history",true);return Map.of("confirmed",true,"nativeResumeConfirmed",true,"automationVerified",true,"newSubmission",false,"resumeName",receipt.get("resumeName"));
        }
        if(!platform.equals("zhilian")||!Set.of("native_attempted","complete").contains(entry.optString("status"))||!zhilianChatUrl(url))throw new IllegalStateException("仅核对智联已尝试投递的站内会话，不重新投递");
        Page receipt=BackgroundBrowserPages.create(p.context());
        try{receipt.navigate(url);boolean selected=false;for(int n=0;n<32&&!chatReceipt(receipt,entry.getJSONObject("job"));n++){if(!selected)selected=selectReceiptConversation(receipt,entry.getJSONObject("job"));receipt.waitForTimeout(250);}check(receipt);
            if(!chatReceipt(receipt,entry.getJSONObject("job"))){
                entry.put("nativeReceiptCheck",new JSONObject().put("url",receipt.url()).put("identity",chatIdentity(receipt,entry.getJSONObject("job")))
                    .put("title",first(receipt,".im-chat-header__job-title")).put("company",first(receipt,".im-chat-header__meta-text")).put("salary",first(receipt,".im-chat-header__salary"))
                    .put("active",receipt.locator(".im-session-item.is-active").allTextContents()).put("bubbles",receipt.locator(".im-message--custom .im-message__bubble--me").allTextContents()));store.save(id,entry);
                throw new IllegalStateException("会话身份或附件简历回执未确认，记录继续锁定");
            }
            confirmNative(id,entry,"zhilian-persisted-chat-attachment",false);return Map.of("confirmed",true,"nativeResumeConfirmed",true,"automationVerified",false,"newSubmission",false);
        }finally{receipt.close();}
    }
    public boolean deliver(JSONObject c,String id,JSONObject entry,Page p,BooleanSupplier stopped) {
        JSONObject config=configuration.get();if(!config.optBoolean("resumeReady"))throw new IllegalStateException("站内简历尚未确认，发送锁定");
        String url=canonical(platform,entry.getJSONObject("job").optString("url"));if(url.isBlank())throw new IllegalArgumentException("岗位网址无效");
        String navigation=navigationUrl(platform,entry.getJSONObject("job").optString("navigationUrl",url));if(!canonical(platform,navigation).equals(url))throw new IllegalArgumentException("岗位跳转网址与已审核岗位不一致");
        p.navigate(navigation);check(p);for(int n=0;n<20&&!loggedIn(p)&&!stopped.getAsBoolean();n++)p.waitForTimeout(200);check(p);if(!loggedIn(p))throw new IllegalStateException("未核对到登录身份，未提交简历");
        if(!same(entry.getJSONObject("job"),readJob(p,url)))throw new IllegalStateException("岗位信息发生变化，请重新判断，未提交");
        if(completed(p)){entry.put("status","complete").put("nativeResumeConfirmed",true).put("alreadySubmittedOnPlatform",true);store.save(id,entry);return false;}
        List<Locator> buttons=applyButtons(p);if(buttons.size()!=1)throw new IllegalStateException("当前岗位投递入口不唯一或需要外部网申，未提交");
        if(stopped.getAsBoolean())return false;entry.put("status","native_attempted");store.save(id,entry);Set<Page> before=new HashSet<>(p.context().pages());buttons.getFirst().click();
        return awaitNativeReceipt(id,entry,p,before,stopped,true);
    }
    public boolean continueExistingDelivery(String id,JSONObject entry,Page p,BooleanSupplier stopped) {
        if(!platform.equals("zhilian")||!entry.optString("status").equals("native_attempted")||!configuration.get().optBoolean("resumeReady"))throw new IllegalStateException("仅继续已记录的智联未完成简历步骤");
        JSONObject job=entry.getJSONObject("job");String url=canonical(platform,job.optString("url")),navigation=navigationUrl(platform,job.optString("navigationUrl",url));
        if(url.isBlank()||!canonical(platform,navigation).equals(url))throw new IllegalStateException("岗位入口未核对");
        p.navigate(navigation);check(p);if(!loggedIn(p)||!same(job,readJob(p,url)))throw new IllegalStateException("岗位或登录身份变化，未继续发送");
        var continued=p.locator("button").all().stream().filter(l->l.isVisible()&&l.innerText().trim().equals("继续沟通")).toList();
        if(continued.size()!=1)throw new IllegalStateException("未找到唯一的继续沟通入口，不重新点击立即投递");
        if(stopped.getAsBoolean())return false;Set<Page> before=new HashSet<>(p.context().pages());entry.put("nativeConversationOpened",true);store.save(id,entry);continued.getFirst().click();return awaitNativeReceipt(id,entry,p,before,stopped,true);
    }
    private boolean awaitNativeReceipt(String id,JSONObject entry,Page original,Set<Page> before,BooleanSupplier stopped,boolean initialSubmission) {
        boolean dialogSubmitted=entry.optBoolean("nativeDialogSubmitted");
        Set<Page> selectedReceipts=new HashSet<>();
        for(int n=0;n<100&&!stopped.getAsBoolean();n++) {
            List<Page> popups=platform.equals("zhilian")?original.context().pages().stream().filter(page->!before.contains(page)&&!page.isClosed()&&zhilianChatUrl(page.url())).toList():List.of();
            if(platform.equals("zhilian")&&popups.isEmpty())popups=original.context().pages().stream().filter(page->!page.isClosed()&&chatIdentity(page,entry.getJSONObject("job"))).toList();
            if(popups.size()>1)throw new IllegalStateException("出现多个智联会话，未选择或发送简历");Page p=popups.size()==1?popups.getFirst():original;
            String host=URI.create(p.url()).getHost(),owned=URI.create(legacyHome()).getHost().replace("www.","");
            if(host==null||!(host.equals(owned)||host.endsWith("."+owned)))throw new IllegalStateException("该岗位跳转外部网申，未继续填写或提交");
            check(p);
            if(platform.equals("zhilian")&&zhilianChatUrl(p.url())&&!selectedReceipts.contains(p)&&!chatIdentity(p,entry.getJSONObject("job"))&&selectReceiptConversation(p,entry.getJSONObject("job")))selectedReceipts.add(p);
            if(platform.equals("job51")&&!job51Id(entry.getJSONObject("job").optString("url")).isBlank()&&p.url().equals("https://jobs.51job.com/applysuccess.php?jobid="+job51Id(entry.getJSONObject("job").optString("url")))) {
                Map<String,Object> receipt=verifyJob51History(p,entry.getJSONObject("job"),stopped);
                if(!receipt.isEmpty()){entry.put("nativeReceiptEvidence",new JSONObject(receipt));confirmNative(id,entry,"job51-application-history",true);return true;}
                throw new IllegalStateException("已进入申请结果页，但未核对到当日申请记录；未重新投递");
            }
            if(zhilianChatUrl(p.url())?chatReceipt(p,entry.getJSONObject("job")):completed(p)){confirmNative(id,entry,zhilianChatUrl(p.url())?"zhilian-chat-attachment":"native-success-receipt",initialSubmission||entry.optBoolean("nativeResumeActionStarted")||entry.optBoolean("nativeDialogSubmitted"));return true;}
            if(platform.equals("zhilian")&&p==original&&!zhilianChatUrl(p.url())&&!entry.optBoolean("nativeConversationOpened")&&canonical(platform,p.url()).equals(canonical(platform,entry.getJSONObject("job").optString("url")))) {
                var continued=p.locator("button").all().stream().filter(l->l.isVisible()&&l.innerText().trim().equals("继续沟通")).toList();
                if(continued.size()==1){if(stopped.getAsBoolean())return false;entry.put("nativeConversationOpened",true);store.save(id,entry);continued.getFirst().click();}
            }
            if(platform.equals("zhilian")&&chatIdentity(p,entry.getJSONObject("job"))&&!entry.optBoolean("nativeResumeActionStarted")) {
                var sendResume=p.locator("button.im-send-resume-nav__chip").all().stream().filter(l->l.isVisible()&&l.innerText().trim().equals("发简历")).toList();
                if(sendResume.size()==1){if(stopped.getAsBoolean())return false;entry.put("nativeResumeActionStarted",true).put("nativePendingChatUrl",p.url());store.save(id,entry);sendResume.getFirst().click();}
            }
            if(zhilianChatUrl(p.url())&&!chatIdentity(p,entry.getJSONObject("job"))){p.waitForTimeout(300);continue;}
            List<Locator> dialogs=p.locator(".el-dialog,[role=dialog]").all().stream().filter(Locator::isVisible).toList();
            if(!dialogSubmitted&&dialogs.size()==1) {
                Locator dialog=dialogs.getFirst();
                if(dialog.locator("input[type=radio]:checked,.el-radio.is-checked").count()==1&&dialog.locator("input:visible:not([type=radio]):not([type=checkbox])").count()==0) {
                    var confirm=dialog.locator("button").filter(new Locator.FilterOptions().setHasText(java.util.regex.Pattern.compile("^(确认投递|投递简历|确认申请)$"))).all().stream().filter(Locator::isVisible).toList();
                    if(confirm.size()==1){if(stopped.getAsBoolean())return false;entry.put("nativeDialogSubmitted",true);store.save(id,entry);confirm.getFirst().click();dialogSubmitted=true;}
                }
            }
            p.waitForTimeout(300);
        }
        entry.put("lastDeliveryIssue","未确认投递成功，请核对站内简历、补充信息或投递记录；禁止盲目重试");store.save(id,entry);throw new IllegalStateException(entry.getString("lastDeliveryIssue"));
    }
    public Map<String,Object> diagnostic(Page p) {
        String url=p.url();String host;try{host=URI.create(url).getHost();}catch(Exception e){host="";}
        String expected=platform.equals("job51")?"51job.com":URI.create(Platforms.home(platform)).getHost().replace("www.","");
        if(host==null||!(host.equals(expected)||host.endsWith("."+expected)))return Map.of("message","不读取其他平台页面");
        Map<String,Object> result=new LinkedHashMap<>(Map.of("url",url,"title",p.title(),"jobLinks",jobUrls(p).size(),"jobExamples",jobUrls(p).stream().limit(5).toList(),"loggedIn",loggedIn(p),"readFailures",store.readFailures().toMap(),"controls",p.locator("header a,nav a,button,h1,[class*=salary],[class*=company]").evaluateAll("els=>els.filter(e=>e.getClientRects().length).slice(0,40).map(e=>({tag:e.tagName,cls:e.className,text:e.innerText?.slice(0,180)}))"),
                "accountControls",p.locator("[class*=user],[class*=login],[class*=nick],[class*=avatar],[class*=member],header,.header,[class*=topbar]").evaluateAll("els=>els.filter(e=>e.getClientRects().length).slice(0,70).map(e=>({tag:e.tagName,cls:e.className,text:e.innerText?.slice(0,300)}))"),
                "navigation",p.locator("a[href]").evaluateAll("els=>els.filter(e=>e.getClientRects().length&&/推荐|我的|职位|简历|登录|退出/.test(e.innerText)).slice(0,35).map(e=>({text:e.innerText.slice(0,80),href:e.href.split('?')[0]}))"),
                "listMessages",p.locator("[class*=empty],[class*=Empty]").evaluateAll("els=>els.filter(e=>e.getClientRects().length).slice(0,12).map(e=>({cls:e.className,text:e.innerText?.slice(0,600)}))")));
        result.put("pageText",first(p,"body"));
        result.put("listControls",p.locator("[class*=job-list],[class*=job-card],[class*=joblist],[class*=city],[class*=City]").evaluateAll("els=>els.filter(e=>e.getClientRects().length).slice(0,65).map(e=>({tag:e.tagName,cls:e.className,text:e.innerText?.slice(0,400),attributes:[...e.attributes].filter(a=>/^(href|data-|role)/.test(a.name)).map(a=>({name:a.name,value:a.value})),children:[...e.children].slice(0,8).map(n=>({tag:n.tagName,cls:n.className}))}))"));return result;
    }
    public Object inspectDetail(Page list,String url) {
        boolean campusListing=platform.equals("zhilian")&&(url.equals("https://xiaoyuan.zhaopin.com/recommend?refcode=4032")||url.equals("https://xiaoyuan.zhaopin.com/recommend"));
        boolean resumeCenter=platform.equals("job51")&&url.equals("https://www.51job.com/resume/center");
        boolean job51History=platform.equals("job51")&&url.equals("https://i.51job.com/userset/my_apply.php");
        boolean job51Receipt=platform.equals("job51")&&url.matches("https://jobs\\.51job\\.com/applysuccess\\.php\\?jobid=\\d+");
        boolean nativeChat=platform.equals("zhilian")&&zhilianChatUrl(url);
        String target=campusListing||resumeCenter||nativeChat||job51Receipt||job51History?url:navigationUrl(platform,url);if(target.isBlank())throw new IllegalArgumentException("只允许读取当前平台的真实岗位链接或已核对的推荐、简历、会话、回执、申请记录入口");
        Page detail=BackgroundBrowserPages.create(list.context());detail.setDefaultTimeout(8000);detail.setDefaultNavigationTimeout(25000);boolean needsAttention=false;
        try {
            detail.navigate(target);if(URI.create(target).getHost().equals("xiaoyuan.zhaopin.com"))for(int n=0;n<50&&first(detail,"body").length()<50;n++)detail.waitForTimeout(200);
            Map<String,Object> result=new LinkedHashMap<>(diagnostic(detail));
            if(job51History){detail.waitForTimeout(2500);check(detail);result=new LinkedHashMap<>(diagnostic(detail));result.put("applicationRows",detail.locator("tr,[class*=apply],[class*=delivery],[class*=history]").evaluateAll("els=>els.filter(e=>e.getClientRects().length).slice(0,45).map(e=>({tag:e.tagName,cls:e.className,text:e.innerText?.slice(0,3000),links:[...e.querySelectorAll('a[href]')].map(a=>({text:a.innerText,href:a.href}))}))"));return result;}
            if(job51Receipt){check(detail);result.put("receiptNodes",detail.locator("div,p,span,h1,h2,h3").evaluateAll("els=>els.filter(e=>!e.children.length&&e.getClientRects().length&&/成功|已投递|失败|未完成/.test(e.innerText)).slice(0,20).map(e=>({tag:e.tagName,cls:e.className,text:e.innerText,parent:e.parentElement.className}))"));return result;}
            if(nativeChat){
                detail.waitForTimeout(2500);check(detail);result=new LinkedHashMap<>(diagnostic(detail));result.put("pageText",first(detail,"body"));
                result.put("chatStructure",detail.locator("[class*=im-chat-header],[class*=im-session-item]").evaluateAll("els=>els.filter(e=>e.getClientRects().length).slice(0,50).map(e=>({tag:e.tagName,cls:e.className,text:e.innerText?.slice(0,1200)}))"));
                result.put("deliveryControls",detail.locator("[class*=resume],[class*=message],[role=dialog],input").evaluateAll("els=>els.filter(e=>e.getClientRects().length).slice(0,90).map(e=>({tag:e.tagName,cls:e.className,text:e.innerText?.slice(0,1600),type:e.type,checked:e.checked}))"));return result;
            }
            if(resumeCenter){
                for(int n=0;n<32&&!first(detail,"body").matches("(?s).*(附件|完整度|编辑简历|简历名称|已上传).*" );n++)detail.waitForTimeout(250);
                check(detail);result=new LinkedHashMap<>(diagnostic(detail));result.put("resumePageText",detail.locator("body").innerText());return result;
            }
            if(campusListing){selectCampusInternship(detail);result=new LinkedHashMap<>(diagnostic(detail));result.put("internshipFilterConfirmed",true);result.put("cards",detail.locator(".position-card").allTextContents().stream().limit(3).toList());return result;}
            result.put("headerAncestors",detail.locator("h1").count()==0?List.of():detail.locator("h1").first().evaluate("el=>{let out=[];for(let n=el,i=0;n&&i<4;n=n.parentElement,i++)out.push({tag:n.tagName,cls:n.className,text:n.innerText?.slice(0,2000),children:[...n.querySelectorAll('*')].slice(0,35).map(e=>({tag:e.tagName,cls:e.className,text:e.children.length?undefined:e.innerText?.slice(0,180)}))});return out}"));
            result.put("readProbe",Map.of("title",first(detail,titleSelectors()),"company",first(detail,companySelectors()),"salary",first(detail,salarySelectors()),"city",first(detail,citySelectors()),"jdLength",first(detail,jdSelectors()).length()));
            try{result.put("job",readJob(detail,target).toMap());}
            catch(AttentionRequired e){needsAttention=true;result.put("jobError",e.getMessage());result.put("requiresUserAction",true);}
            catch(PlaywrightException e){result.put("jobError","岗位页面加载或必要字段读取超时，未获得完整详情");}
            catch(IllegalStateException e){result.put("jobError",e.getMessage());}
            result.put("detailElements",detail.locator("h1,h2,[class*=salary],[class*=describ],[class*=job-title],[class*=job-des],[class*=jobDetail],[class*=jobdetail],[class*=company-name]")
                    .evaluateAll("els=>els.filter(e=>e.getClientRects().length).slice(0,35).map(e=>({tag:e.tagName,cls:e.className,text:e.innerText?.slice(0,2500)}))"));
            result.put("descriptionSections",detail.locator("h2").filter(new Locator.FilterOptions().setHasText(java.util.regex.Pattern.compile("^(职位描述|实习职位特点)$"))).evaluateAll("els=>els.map(e=>({cls:e.parentElement.className,text:e.parentElement.innerText.slice(0,4000),children:[...e.parentElement.querySelectorAll('*')].slice(0,35).map(n=>({tag:n.tagName,cls:n.className,text:n.children.length?undefined:n.innerText?.slice(0,160)}))}))"));return result;
        }catch(PlaywrightException e){return Map.of("url",target,"jobError","岗位页面加载失败，请稍后检查网络或该平台页面");}
        finally{if(!needsAttention)detail.close();}
    }
}
