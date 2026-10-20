package com.getjobs.worker.boss;

import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;

/** Called by failure paths only. No polling, model calls or automatic recovery. */
@Component
public class InternshipFailureNotifier {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(InternshipFailureNotifier.class);
    private final Path directory,script;
    @Value("${internship.live-enabled:false}") boolean enabled;
    public InternshipFailureNotifier() {this(Path.of("data","internship","notifications"),Path.of("..","显示投递异常通知.ps1"));}
    InternshipFailureNotifier(Path directory,Path script) {this.directory=directory;this.script=script;}
    public static InternshipFailureNotifier isolated(Path directory,Path script,boolean enabled) {
        var notifier=new InternshipFailureNotifier(directory,script);notifier.enabled=enabled;return notifier;
    }
    public void report(String type,String round,String message) {
        report(new JSONObject().put("type",type).put("round",round).put("message",message)
                .put("paused",true).put("time",Instant.now().toString()));
    }
    public void report(JSONObject eventBody) {
        if(!enabled)return;
        try {
            Files.createDirectories(directory);
            Path event=directory.resolve(java.util.UUID.randomUUID()+".json");
            Files.writeString(event,eventBody.toString());
            launch(event);
        } catch(Exception e) {log.error("桌面异常通知失败，投递仍保持暂停，请查看工作台记录",e);}
    }
    void launch(Path event) throws IOException {
        if(!Files.isRegularFile(script))throw new IOException("通知脚本缺失："+script);
        String windows=System.getenv("WINDIR");
        if(windows==null)throw new IOException("桌面通知仅支持Windows");
        new ProcessBuilder(Path.of(windows,"System32","WindowsPowerShell","v1.0","powershell.exe").toString(),
                "-NoProfile","-NonInteractive","-WindowStyle","Hidden","-ExecutionPolicy","Bypass",
                "-File",script.toAbsolutePath().normalize().toString(),"-EventFile",event.toAbsolutePath().toString())
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(directory.resolve("desktop.log").toFile())).start();
    }
}
