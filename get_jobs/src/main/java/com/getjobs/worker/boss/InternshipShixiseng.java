package com.getjobs.worker.boss;

import com.microsoft.playwright.*;
import org.json.*;
import org.springframework.stereotype.Service;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.function.*;

/** Native website workflow. All browser calls run on the manager's Playwright thread. */
@Service
public class InternshipShixiseng {
    static final String HOME="https://www.shixiseng.com/";
    private final InternshipStore store;
    private Page page;
    private volatile Map<String,Object> session=Map.of("loggedIn",false,"message","请在专用Chrome登录实习僧");
    @org.springframework.beans.factory.annotation.Autowired(required=false) private InternshipFailureNotifier notifier;
    public InternshipShixiseng(InternshipStore store) {this.store=store;}
    public Map<String,Object> status() {Map<String,Object> state=new LinkedHashMap<>(session);state.put("liveSubmissionVerified",store.shixisengState().optBoolean("liveSubmissionVerified"));return state;}
    public Map<String,Object> diagnostic(Page boss) {
        Page p=page(boss);String url=p.url();
        if(!url.split("\\?")[0].equals(HOME)&&!validUrl(url)&&!url.startsWith(HOME+"interns?"))
            return Map.of("message","当前页面不是实习僧公开岗位页，未读取内容");
        String body=text(p,"body");
        return Map.of("url",url,"title",p.title(),"cards",p.locator(".intern-content-href,.intern-wrap").count(),
                "content",body.substring(0,Math.min(body.length(),4500)),
                "controls",p.locator("input,select,button,[role=dialog],[class*=resume],[class*=deliver],[class*=supplement]").evaluateAll("els=>els.filter(e=>e.getClientRects().length).slice(-70).map(e=>({tag:e.tagName,cls:e.className,type:e.type,name:e.name,checked:e.checked,text:e.innerText?.slice(0,900),options:e.tagName==='SELECT'?Array.from(e.options).map(o=>({value:o.value,text:o.text,selected:o.selected})):undefined}))"),
                "nodes",p.locator(".intern-content-href,.intern-wrap,a[href*='/intern/']").evaluateAll("els=>els.slice(0,6).map(e=>({tag:e.tagName,attrs:Array.from(e.attributes).map(a=>[a.name,a.value]),text:e.innerText.slice(0,180)}))"));
    }
    Page page(Page boss) {
        if(page==null||page.isClosed()) {page=boss.context().newPage();page.setDefaultTimeout(8000);page.setDefaultNavigationTimeout(25000);}
        return page;
    }
    static boolean validUrl(String url) {return url.matches("https://www\\.shixiseng\\.com/intern/inn_[A-Za-z0-9]+(?:\\?.*)?");}
    static String text(Page p,String selector) {
        Locator items=p.locator(selector);return items.count()==0?"":items.first().innerText().trim();
    }
    static boolean loggedIn(Page p) {return p.locator(".logined .mine").all().stream().anyMatch(Locator::isVisible)&&!text(p,".logined .nickname").isBlank();}
    public Map<String,Object> checkSession(Page boss) {
        Page p=page(boss);p.navigate(HOME);p.bringToFront();
        try {p.locator(".login-status").first().waitFor();p.waitForTimeout(1000);}catch(PlaywrightException ignored) {}
        boolean logged=loggedIn(p);
        session=Map.of("loggedIn",logged,"account",logged?text(p,".logined .nickname"):"","persistent",true,
                "message",logged?"实习僧已登录，使用本机专用Chrome持久会话":"请在专用Chrome手动登录实习僧，再点击检查登录态");
        return session;
    }
    static void checkPage(Page p) {
        String body=text(p,"body");
        if(p.url().contains("/login")||body.matches("(?is)^.*(403 Forbidden|访问被拒绝|访问受限|请完成验证|安全验证|滑动验证).*$"))
            throw new IllegalStateException("实习僧要求登录或验证，请在专用Chrome处理；未提交简历");
        if(body.matches("(?s).*(今日|今天|当日).*(投递|简历).*(上限|用完|用尽).*"))
            throw new IllegalStateException("实习僧提示当日投递上限，今天不再提交");
    }
    static List<String> urls(Page p) {
        LinkedHashSet<String> found=new LinkedHashSet<>();
        Object raw=p.locator("a[href*='/intern/inn_'],.intern-content-href[point-id],.intern-wrap[data-intern-id]")
                .evaluateAll("els=>els.map(e=>e.href||('https://www.shixiseng.com/intern/'+(e.getAttribute('point-id')||e.getAttribute('data-intern-id')||'')))");
        for(Object value:(List<?>)raw) {String url=value.toString().split("\\?")[0];if(validUrl(url))found.add(url);}
        return new ArrayList<>(found);
    }
    static JSONObject readJob(Page p,String url) {
        if(!validUrl(url))throw new IllegalArgumentException("实习僧岗位链接无效");
        checkPage(p);p.locator(".job_part .job_detail").first().waitFor();
        String jd=String.join("\n",p.locator(".job_part .job_detail").allInnerTexts()).trim();
        String schedule=String.join(" · ",p.locator(".job_week,.job_time,.job_academic").allInnerTexts());
        return new JSONObject().put("platform","shixiseng").put("url",url.split("\\?")[0])
                .put("title",text(p,".new_job_name > span")).put("company",text(p,".com-name"))
                .put("city",text(p,".job_position")).put("salary",text(p,".job_money"))
                .put("employmentType","实习").put("jd",jd+"\n"+schedule).put("deliveryMethod","native-resume");
    }
    public void collect(JSONObject c,Page boss,BooleanSupplier stopped,Consumer<JSONObject> accept,Consumer<String> progress) {
        Page list=page(boss);Set<String> seen=new HashSet<>();List<String> sources=new ArrayList<>();
        if(c.getString("source").equals("recommend"))sources.add(HOME);
        else for(Object word:c.getJSONArray("keywords"))sources.add(HOME+"interns?type=intern&keyword="+URLEncoder.encode(word.toString(),StandardCharsets.UTF_8));
        int count=0;
        for(String source:sources)for(int pageNo=1;pageNo<=c.getInt("refreshRounds")&&!stopped.getAsBoolean();pageNo++) {
            if(count>=c.getInt("maxScan"))return;
            list.navigate(source.equals(HOME)?HOME:source+"&page="+pageNo);checkPage(list);
            try {list.locator(".intern-content-href,.intern-wrap,a[href*='/intern/inn_']").first().waitFor();}catch(PlaywrightException ignored) {}
            List<String> jobs=urls(list);
            for(int wait=0;jobs.isEmpty()&&wait<40&&!stopped.getAsBoolean();wait++) {list.waitForTimeout(250);jobs=urls(list);}
            if(jobs.isEmpty()&&text(list,"body").matches("(?s).*(暂无.*职位|没有找到.*职位|未找到.*职位).*"))break;
            if(jobs.isEmpty())throw new IllegalStateException("实习僧未读取到真实岗位列表，请检查登录或筛选条件");
            for(String url:jobs) {
                if(stopped.getAsBoolean()||count>=c.getInt("maxScan"))return;
                if(!seen.add(url)||store.blocksRetry(store.get(InternshipPolicy.hash(url))))continue;
                Page detail=list.context().newPage();detail.setDefaultTimeout(8000);detail.setDefaultNavigationTimeout(25000);
                try {
                    detail.navigate(url);JSONObject job=readJob(detail,url);
                    if(!InternshipPolicy.formalReason(job).isBlank()) {accept.accept(job);continue;}
                    progress.accept("实习僧：读取并评分第 "+(++count)+" 个候选 · "+job.optString("title"));accept.accept(job);
                } finally {detail.close();}
                for(int wait=0;wait<c.getInt("intervalSeconds")*5&&!stopped.getAsBoolean();wait++)list.waitForTimeout(200);
            }
            if(source.equals(HOME))break;
        }
    }
    public Map<String,Object> openJob(Page boss,JSONObject expected) {
        if(!validUrl(expected.optString("url")))throw new IllegalArgumentException("岗位链接无效");
        Page p=page(boss);p.navigate(expected.getString("url"));p.bringToFront();
        JSONObject actual=readJob(p,expected.getString("url"));
        return Map.of("opened",true,"verified",same(expected,actual),"message","已在专用Chrome打开实习僧原岗位，未提交简历");
    }
    static boolean same(JSONObject a,JSONObject b) {
        for(String field:List.of("title","company","city","salary","jd"))
            if(a.optString(field).isBlank()||!a.optString(field).replaceAll("\\s+","").equals(b.optString(field).replaceAll("\\s+","")))return false;
        return true;
    }
    static void prepareNativeForm(JSONObject c,Page p,Locator dialog) {
        if(!java.util.Set.of("attachment","online").contains(c.optString("shixisengResumeType"))
                ||c.optString("shixisengPeriod").isBlank()||c.optInt("shixisengDays",0)<1)
            throw new IllegalStateException("请先保存自己的简历类型、实习时长与每周出勤天数，未提交");
        String type=c.optString("shixisengResumeType","attachment").equals("attachment")?"附件简历":"在线简历";
        Locator rows=dialog.locator(".user-resume").filter(new Locator.FilterOptions().setHasText(type));
        if(rows.count()!=1)throw new IllegalStateException("实习僧未找到唯一的"+type+"，未提交");
        Locator chinese=rows.locator(".right-item__left").filter(new Locator.FilterOptions().setHasText(java.util.regex.Pattern.compile("^中文[：:].*")));
        if(chinese.count()!=1)throw new IllegalStateException("实习僧中文简历选项不唯一，未提交");
        chinese.click();
        if(rows.locator(".right-item__img img[src*='radio-choosed']").count()!=1)throw new IllegalStateException("实习僧所选简历未确认，未提交");
        String arrival=c.optString("shixisengArrival");if(arrival.isBlank())throw new IllegalStateException("请保存实习僧到岗时间，未提交");
        selectNativeOption(p,dialog,"到岗时间",arrival);
        selectNativeOption(p,dialog,"实习时长",c.getString("shixisengPeriod"));
        selectNativeOption(p,dialog,"每周出勤",c.getInt("shixisengDays")+"天");
    }
    static String nativeLabel(String value) {return value.trim().replace("一周","1周").replace("两周","2周");}
    static void selectNativeOption(Page p,Locator dialog,String label,String value) {
        Locator input=dialog.locator(".complete-info__item").filter(new Locator.FilterOptions().setHasText(label)).locator("input");
        if(input.count()!=1)throw new IllegalStateException("实习僧"+label+"控件不唯一，未提交");
        if(nativeLabel(input.inputValue()).equals(nativeLabel(value)))return;
        input.click();
        Locator option=p.locator(".el-select-dropdown__item");
        List<Locator> visible=List.of();
        for(int wait=0;wait<20;wait++) {
            visible=option.all().stream().filter(l->l.isVisible()&&nativeLabel(l.innerText()).equals(nativeLabel(value))).toList();
            if(visible.size()==1)break;p.waitForTimeout(100);
        }
        if(visible.size()!=1)throw new IllegalStateException("实习僧"+label+"无法选择已保存值："+value+"，未提交");
        visible.getFirst().click();if(!nativeLabel(input.inputValue()).equals(nativeLabel(value)))throw new IllegalStateException("实习僧"+label+"保存未确认，未提交");
    }
    public boolean deliver(JSONObject c,String id,JSONObject entry,Page boss,BooleanSupplier stopped) {
        if(!c.optBoolean("shixisengResumeConfirmed"))throw new IllegalStateException("请先登录并核对实习僧站内简历，再确认允许使用该简历投递");
        if(!validUrl(entry.getJSONObject("job").optString("url")))throw new IllegalArgumentException("实习僧岗位链接无效");
        String today=LocalDate.now(InternshipSchedule.ZONE).toString();
        if(today.equals(store.shixisengState().optString("quotaDate")))throw new IllegalStateException("实习僧今日已达投递上限");
        Page p=page(boss);p.navigate(entry.getJSONObject("job").getString("url"));checkPage(p);
        if(!loggedIn(p))throw new IllegalStateException("实习僧未登录，请在专用Chrome登录；未提交简历");
        if(!same(entry.getJSONObject("job"),readJob(p,p.url())))throw new IllegalStateException("实习僧岗位信息已变化，请重新判断；未提交简历");
        Locator apply=p.locator(".resume_apply").filter(new Locator.FilterOptions().setHasText("投个简历"));
        if(p.locator(".resume_apply").all().stream().anyMatch(l->l.isVisible()&&l.innerText().trim().equals("已投递"))) {
            entry.put("status","complete").put("nativeResumeConfirmed",true).put("alreadySubmittedOnPlatform",true);store.save(id,entry);return false;
        }
        if(stopped.getAsBoolean())return false;
        entry.put("status","native_attempted");store.save(id,entry);
        try {
            apply.all().stream().filter(Locator::isVisible).findFirst().orElseThrow(()->new IllegalStateException("未找到站内投递入口，可能已过期或要求外部网申")).click();
            boolean confirmedDialog=false;
            for(int i=0;i<100&&!stopped.getAsBoolean();i++) {
                checkPage(p);
                if(p.locator(".resume_apply").all().stream().anyMatch(l->l.isVisible()&&l.innerText().trim().equals("已投递"))) {
                    entry.remove("lastDeliveryIssue");entry.put("status","complete").put("nativeResumeConfirmed",true);store.save(id,entry);
                    store.saveShixisengState(store.shixisengState().put("liveSubmissionVerified",true).put("verifiedAt",Instant.now().toString()).put("verifiedRecord",id));return true;
                }
                List<Locator> dialogs=p.locator(".el-dialog,[role=dialog]").all().stream().filter(Locator::isVisible).toList();
                if(!confirmedDialog&&dialogs.size()==1) {
                    Locator dialog=dialogs.getFirst();
                    if(dialog.locator(".common-deliver").count()==1) {
                        prepareNativeForm(c,p,dialog);
                        if(stopped.getAsBoolean())return false;
                        Locator submit=dialog.locator(".common-deliver__footer .btn").filter(new Locator.FilterOptions().setHasText(java.util.regex.Pattern.compile("^\\s*确认投递\\s*$")));
                        if(submit.count()!=1)throw new IllegalStateException("实习僧最终确认入口不唯一，未提交");
                        entry.put("nativeForm",new JSONObject().put("resumeType",c.getString("shixisengResumeType")).put("arrival",c.optString("shixisengArrival")).put("period",c.getString("shixisengPeriod")).put("days",c.getInt("shixisengDays")));
                        store.save(id,entry);submit.click();confirmedDialog=true;
                    }
                    if(!confirmedDialog&&dialog.locator("input[type=radio]:checked,.el-radio.is-checked").count()>0) {
                        Locator submit=dialog.locator("button").filter(new Locator.FilterOptions().setHasText(java.util.regex.Pattern.compile("^(确认投递|确定投递|投递简历|投递)$")));
                        if(submit.count()==1) {if(stopped.getAsBoolean())return false;submit.click();confirmedDialog=true;}
                    }
                }
                p.waitForTimeout(300);
            }
            throw new IllegalStateException("实习僧投递未确认；可能需选择站内简历或完成资料，请核对后处理，禁止盲目重试");
        } catch(RuntimeException e) {
            entry.put("lastDeliveryIssue",e.getMessage());store.save(id,entry);
            if(e.getMessage()!=null&&e.getMessage().contains("当日投递上限")) {
                store.saveShixisengState(new JSONObject().put("quotaDate",today).put("reason",e.getMessage()));
                store.platformQuotaReached("shixiseng",today,e.getMessage());
            }
            throw e;
        }
    }
}
