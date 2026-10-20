package com.getjobs.worker.parallel;

import com.getjobs.worker.boss.*;
import org.json.JSONObject;
import java.nio.file.Path;

public class PlatformStore extends InternshipStore {
    private final ParallelRepository repository;
    private final String platform;
    public PlatformStore(ParallelRepository repository,String platform) {
        super(Path.of("data","parallel","platforms",Platforms.require(platform)));this.repository=repository;this.platform=platform;
    }
    @Override protected JSONObject read(String name) {return repository.document(platform,name);}
    @Override protected void write(String name,JSONObject value) {repository.document(platform,name,value);}
    @Override public synchronized void finishScheduled(String slot,String message,boolean paused,boolean deadline,JSONObject summary) {
        JSONObject before=scheduleState();boolean active=before.has("active");
        String day=java.time.LocalDate.now(RoundDispatcher.ZONE).toString();
        super.finishScheduled(slot,message,paused,deadline,summary);
        boolean quota=day.equals(scheduleState().getJSONObject("platforms").getJSONObject(platform).optString("quotaDate"));
        boolean normalCutoff=deadline&&java.time.ZonedDateTime.now(RoundDispatcher.ZONE).getHour()>=17;
        if(active&&paused&&deadline&&!normalCutoff)holdPlatform(platform,day,"本轮运行超过时限，已暂停该平台，待核对后恢复");
        if(active&&paused&&!normalCutoff&&!quota&&!message.contains("暂停请求")&&!message.contains("用户手动暂停"))repository.event(platform,slot,"round_failed",deadline?"本轮运行超过时限，已停止新增投递；"+message:message);
    }
    @Override public synchronized void platformQuotaReached(String p,String day,String reason) {
        boolean already=day.equals(scheduleState().getJSONObject("platforms").getJSONObject(p).optString("quotaDate"));
        String round=scheduleState().optJSONObject("active")==null?day:scheduleState().getJSONObject("active").optString("id",day);
        super.platformQuotaReached(p,day,reason);if(!already)repository.event(platform,round,"daily_limit",reason+"；该平台今天已停止投递，其他平台可继续");
    }
}
