package com.getjobs.application.controller;

import com.getjobs.worker.parallel.*;
import org.json.JSONObject;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.Map;

@RestController
@RequestMapping("/api/v2")
public class ParallelController {
    private final ParallelWorkbench workbench;
    private final org.springframework.context.ConfigurableApplicationContext application;
    @org.springframework.beans.factory.annotation.Autowired private com.getjobs.application.service.BossResumeImageService images;
    public ParallelController(ParallelWorkbench workbench,org.springframework.context.ConfigurableApplicationContext application){this.workbench=workbench;this.application=application;}
    @GetMapping("/status") public Object status(){return workbench.status();}
    @GetMapping("/profile") public String profile(){return workbench.profile().toString();}
    @GetMapping("/resume-image") public Object resumeImage(){return Map.of("exists",images.getCurrentImagePath().isPresent());}
    @PostMapping("/resume-image") public Object uploadImage(@RequestParam("file") org.springframework.web.multipart.MultipartFile file) throws java.io.IOException {
        for(Object platform:(java.util.List<?>)workbench.status().get("platforms"))if(Boolean.TRUE.equals(((Map<?,?>)platform).get("running")))throw new IllegalStateException("请等待各平台轮次结束后更换简历图片");
        images.save(file);return Map.of("saved",true,"exists",true);
    }
    @PutMapping("/profile") public Object profile(@RequestBody String body){workbench.saveProfile(new JSONObject(body));return Map.of("saved",true,"effect","下一轮生效；当前轮使用启动快照");}
    @PutMapping("/platforms/{id}/config") public Object config(@PathVariable String id,@RequestBody String body){workbench.saveConfig(id,new JSONObject(body));return Map.of("saved",true);}
    @PostMapping("/platforms/{id}/start") public Object start(@PathVariable String id,@RequestBody String body){workbench.start(id,new JSONObject(body).optString("source","recommend"));return Map.of("queued",true);}
    @PostMapping("/platforms/{id}/pause") public Object pause(@PathVariable String id){workbench.pause(id);return Map.of("paused",true);}
    @PostMapping("/platforms/{id}/resume") public Object resume(@PathVariable String id){workbench.resume(id);return Map.of("resumed",true);}
    @PostMapping("/pause-all") public Object pauseAll(){workbench.pauseAll();return Map.of("paused",true);}
    @PutMapping("/schedule") public Object schedule(@RequestBody String body){workbench.enableSchedule(new JSONObject(body).getBoolean("enabled"));return workbench.status();}
    @PostMapping("/platforms/{id}/session") public Object session(@PathVariable String id){return workbench.checkSession(id);}
    @GetMapping("/platforms/{id}/diagnostic") public Object diagnostic(@PathVariable String id,@RequestParam(required=false)String url){return workbench.diagnostic(id,url);}
    @GetMapping("/platforms/{id}/records") public String records(@PathVariable String id,@RequestParam(required=false)String round){return workbench.records(id,round).toString();}
    @GetMapping("/rounds") public String rounds(@RequestParam(required=false)String platform){return workbench.rounds(platform).toString();}
    @GetMapping("/events") public String events(){return workbench.events().toString();}
    @PostMapping("/model-check") public Object modelCheck(){return workbench.checkModel();}
    @PostMapping("/platforms/{id}/records/{job}/reassess") public Object reassess(@PathVariable String id,@PathVariable String job){workbench.reassess(id,job);return Map.of("started",true);}
    @PostMapping("/platforms/{id}/records/{job}/deliver") public Object deliver(@PathVariable String id,@PathVariable String job,@RequestBody String body){workbench.deliver(id,job,new JSONObject(body).optBoolean("acceptance"));return Map.of("started",true);}
    @PostMapping("/platforms/{id}/records/{job}/verify-receipt") public Object verifyReceipt(@PathVariable String id,@PathVariable String job,@RequestBody String body){return workbench.verifyReceipt(id,job,new JSONObject(body).getString("url"));}
    @PostMapping("/platforms/{id}/records/{job}/continue-native") public Object continueNative(@PathVariable String id,@PathVariable String job){workbench.continueNative(id,job);return Map.of("started",true,"scope","existing-conversation-only");}
    @PostMapping("/platforms/{id}/records/{job}/reconcile") public Object reconcile(@PathVariable String id,@PathVariable String job,@RequestBody String body){workbench.reconcile(id,job,new JSONObject(body));return Map.of("saved",true);}
    @PostMapping("/shutdown") public Object shutdown(){workbench.pauseAll();new Thread(()->{try{Thread.sleep(300);}catch(InterruptedException e){Thread.currentThread().interrupt();}application.close();}).start();return Map.of("stopping","development-only");}
    @ExceptionHandler({IllegalStateException.class,IllegalArgumentException.class}) public ResponseEntity<?> error(RuntimeException e){return ResponseEntity.badRequest().body(Map.of("error",e.getMessage()==null?"操作失败":e.getMessage()));}
}
