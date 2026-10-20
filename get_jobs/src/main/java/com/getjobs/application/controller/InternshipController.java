package com.getjobs.application.controller;

import com.getjobs.worker.boss.*;
import org.json.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;

@RestController
@RequestMapping("/api/internship")
public class InternshipController {
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.context.ConfigurableApplicationContext applicationContext;
    @org.springframework.beans.factory.annotation.Autowired private InternshipSchedule schedule;
    private final InternshipStore store;
    private final InternshipRunner runner;
    public InternshipController(InternshipStore store, InternshipRunner runner) { this.store=store; this.runner=runner; }
    @GetMapping("/settings") public String settings() { return store.settings().toString(); }
    @PutMapping("/settings") public String save(@RequestBody String body) {
        if (runner.isRunning()) throw new IllegalStateException("暂停并等待本轮结束后修改资料");
        JSONObject c = InternshipPolicy.defaults(), submitted = new JSONObject(body);
        for(String key: submitted.keySet()) {
            if (!c.has(key)) throw new IllegalArgumentException("未知配置字段：" + key);
            c.put(key, submitted.get(key));
        }
        store.saveSettings(c); return c.toString();
    }
    @GetMapping("/records") public String records() {
        JSONObject records=store.candidateRecords(),settings=store.settings();String current=InternshipPolicy.fingerprint(settings);
        for(String id:records.keySet()) {
            JSONObject entry=records.getJSONObject(id);
            entry.put("assessmentCurrent",entry.optString("settingsHash").equals(current));
            entry.put("manualConfirmed",InternshipPolicy.manuallyConfirmed(settings,entry));
            if(entry.getJSONObject("result").optString("decision").equals("review"))
                entry.put("manualReviewBlockedReason",InternshipPolicy.manualReviewBlockReason(settings,entry.getJSONObject("job")));
        }
        return records.toString();
    }
    @PostMapping("/archive-formal") public Map<String,Object> archiveFormal() {
        if(runner.isRunning())throw new IllegalStateException("请先暂停本轮");
        return Map.of("archived",store.archiveFormalRecords());
    }
    @PostMapping("/assess/{id}") public String assess(@PathVariable String id,@RequestBody String body) {
        if(runner.isRunning()) throw new IllegalStateException("请等本轮结束后核对判断");
        JSONObject entry=store.get(id), c=store.settings();
        if(entry==null || !entry.optString("status").equals("screened")
                || !entry.optString("settingsHash").equals(InternshipPolicy.fingerprint(c)))
            throw new IllegalArgumentException("岗位缺失、已尝试发送或资料已变化，请重新采集");
        JSONObject result=InternshipPolicy.evaluate(c,entry.getJSONObject("job"),body);
        entry.put("result",result).put("approved",false).put("assessmentSource","chat-reviewed");
        store.save(id,entry);return result.toString();
    }
    @GetMapping("/status") public Map<String,Object> status() { return runner.status(); }
    @PostMapping("/shixiseng-session") public Map<String,Object> shixisengSession() {return runner.checkShixisengSession();}
    @PostMapping("/shixiseng-check") public Map<String,Object> shixisengCheck(@RequestParam(defaultValue="")String source) {runner.checkShixisengRead(source);return runner.status();}
    @GetMapping("/shixiseng-page") public Object shixisengPage() {return runner.shixisengDiagnostic();}
    @GetMapping("/schedule") public Map<String,Object> schedule() {return schedule.status();}
    @PutMapping("/schedule") public Map<String,Object> schedule(@RequestBody String body) {
        schedule.setEnabled(new JSONObject(body).getBoolean("enabled"));return schedule.status();
    }
    @PostMapping("/schedule/wake") public Map<String,Object> wakeSchedule() {schedule.tick();return schedule.status();}
    @PostMapping("/model-check") public Map<String,Object> modelCheck() { return runner.checkModel(); }
    @PostMapping("/preview/{id}") public String preview(@PathVariable String id) { return runner.preview(id).toString(); }
    @PostMapping("/inspect-conversation/{id}") public Object inspectConversation(@PathVariable String id) {return runner.inspectConversation(id);}
    @PostMapping("/reanalyze/{id}") public Map<String,Object> reanalyze(@PathVariable String id) {
        runner.reassess(id);return runner.status();
    }
    @PostMapping("/deliver/{id}") public Map<String,Object> deliver(@PathVariable String id) {
        runner.deliver(id);return runner.status();
    }
    @PostMapping("/confirm-review/{id}") public Map<String,Object> confirmReview(@PathVariable String id,@RequestBody String body) {
        if(runner.isRunning())throw new IllegalStateException("请先暂停本轮");
        store.confirmReview(id,new JSONObject(body).optString("note"));return Map.of("confirmed",true);
    }
    @PostMapping("/boss-session") public Map<String,Object> bossSession() {return runner.checkBossSession();}
    @PostMapping("/open-job/{id}") public Map<String,Object> openJob(@PathVariable String id) {return runner.openJob(id);}
    @GetMapping("/page-snapshot") public Object pageSnapshot() { return runner.pageSnapshot(); }
    @PostMapping("/approve/{id}") public Map<String,Object> approve(@PathVariable String id) {
        if(runner.isRunning()) throw new IllegalStateException("请先暂停本轮");
        store.approve(id); return Map.of("approved", true);
    }
    @PostMapping("/start") public Map<String,Object> start() { runner.start(); return runner.status(); }
    @PostMapping("/reconcile/{id}") public Map<String,Object> reconcile(@PathVariable String id,@RequestBody String body) {
        if(runner.isRunning()) throw new IllegalStateException("请先暂停本轮");
        JSONObject request=new JSONObject(body);
        store.reconcile(id,request.getString("outcome"),request.getString("note"),request.optBoolean("verified"));
        return Map.of("reconciled",true);
    }
    @PostMapping("/stop") public Map<String,Object> stop() { runner.stop(); return runner.status(); }
    @PostMapping("/shutdown") public Map<String,Object> shutdown() {
        if(runner.isRunning())throw new IllegalStateException("请先暂停并等本轮结束");
        new Thread(()->{
            try {Thread.sleep(500);runner.prepareShutdown();applicationContext.close();}
            catch(Exception ignored) { /* The launcher reports a timeout if graceful shutdown fails. */ }
        },"workbench-shutdown").start();
        return Map.of("closing",true);
    }

    @PostMapping("/resume-text") public Map<String,String> resume(@RequestParam("file") MultipartFile file) throws Exception {
        if(file.isEmpty() || file.getSize()>10*1024*1024) throw new IllegalArgumentException("文件为空或超过10MB");
        String name=Objects.requireNonNullElse(file.getOriginalFilename(), "").toLowerCase(Locale.ROOT);
        byte[] bytes=file.getBytes(); String text;
        if(name.endsWith(".pdf")) {
            try(var pdf=Loader.loadPDF(bytes)) { text=new PDFTextStripper().getText(pdf); }
        } else if(name.endsWith(".docx")) {
            text="";
            try(var zip=new ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
                java.util.zip.ZipEntry entry;
                while((entry=zip.getNextEntry())!=null) if(entry.getName().equals("word/document.xml")) {
                    byte[] xml=zip.readNBytes(4*1024*1024+1);
                    if(xml.length>4*1024*1024) throw new IllegalArgumentException("Word内容过大");
                    var factory=DocumentBuilderFactory.newInstance();
                    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
                    factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
                    factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
                    var document=factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xml));
                    var nodes=document.getElementsByTagName("w:p");
                    StringBuilder result=new StringBuilder();
                    for(int i=0;i<nodes.getLength();i++) result.append(nodes.item(i).getTextContent()).append('\n');
                    text=result.toString(); break;
                }
            }
        } else if(name.endsWith(".txt") || name.endsWith(".md")) text=new String(bytes,StandardCharsets.UTF_8);
        else throw new IllegalArgumentException("支持PDF、DOCX、UTF-8 TXT/MD；图片简历请单独上传");
        if(text.isBlank()) throw new IllegalArgumentException("未提取到文本；扫描PDF请先OCR或粘贴文本");
        if(text.length()>50000) throw new IllegalArgumentException("文本过长");
        return Map.of("text",text,"note","本地解析结果，请核对后再确认，尚未调用模型");
    }

    @PostMapping("/simulate") public String simulate(@RequestBody String body) throws Exception {
        String scenario=new JSONObject(body).optString("scenario","analysis");
        JSONObject c=InternshipPolicy.defaults().put("confirmed",true).put("resumeText","参与库存分析工具的用户研究，整理需求并编写PRD。")
                .put("targetDirections","库存数据分析实习").put("excludedDirections","装卸搬运、装卸作业");
        String jd=switch(scenario) {
            case "analysis" -> "负责库存分析工具的用户研究、需求分析和PRD编写。招募产品实习生。";
            case "legal" -> "负责仓库装卸搬运、货物盘点和入库登记。招募仓库作业实习生。";
            case "mixed" -> "参与库存统计工具的需求整理，同时支持日常货物搬运；职责比例未说明。";
            default -> throw new IllegalArgumentException("演示场景无效");
        };
        String decision=scenario.equals("analysis")?"apply":scenario.equals("legal")?"skip":"review";
        JSONObject job=new JSONObject().put("title","虚构演示实习岗位").put("company","虚构演示公司").put("jd",jd).put("salary","300元/天");
        JSONObject model=new JSONObject().put("decision",decision).put("category",scenario.equals("analysis")?"库存数据分析":"搬运或混合职责")
                .put("confidence",0.95).put("matchScore",decision.equals("skip")?10:decision.equals("review")?55:90).put("reason","模拟模型响应，用于验证规则与投递流程；不证明模型真实识别准确率")
                .put("jdEvidence",new JSONArray().put(jd)).put("resumeEvidence",new JSONArray().put("用户研究"))
                .put("unknowns",new JSONArray()).put("constraintViolations",new JSONArray());
        JSONObject result=InternshipPolicy.evaluate(c,job,model.toString());
        List<String> actions=new ArrayList<>();
        if(result.getString("decision").equals("apply")) {
            var simulationRoot=java.nio.file.Path.of("data","internship","simulation");
            java.nio.file.Files.createDirectories(simulationRoot);
            var demoStore=new InternshipStore(java.nio.file.Files.createTempDirectory(simulationRoot,"run-"));
            InternshipDelivery.send(demoStore,"demo",new JSONObject().put("status","screened"),"演示招呼语",()->false,new InternshipDelivery.Port(){
                public boolean targetMatches(){return true;}
                public boolean openChat(){actions.add("立即沟通（模拟）");return true;}
                public boolean sendAndConfirmText(String s){actions.add("文字发送并确认（模拟）");return true;}
                public boolean sendAndConfirmImage(){actions.add("图片发送并确认（模拟）");return true;}
                public void returnToList(){actions.add("返回列表（模拟）");}
            });
        }
        return new JSONObject().put("simulation",true).put("job",job).put("result",result).put("actions",actions).toString();
    }
    @ExceptionHandler({IllegalStateException.class,IllegalArgumentException.class})
    public ResponseEntity<Map<String,String>> error(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("error",e.getMessage()));
    }
}
