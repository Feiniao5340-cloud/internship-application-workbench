package com.getjobs.worker.parallel;

import org.json.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ParallelNotificationTest {
    @TempDir Path directory;
    ParallelRepository repo;
    List<JSONObject> notices;
    @BeforeEach void setup(){repo=new ParallelRepository(directory.resolve("events.db"));notices=new ArrayList<>();repo.onEvent(event->{assertTrue(new ParallelRepository(directory.resolve("events.db")).events().toString().contains(event.getString("id")));notices.add(event);});}
    @Test void reportsEachFailedRoundOnceAfterCommitAndDoesNotPollOrReportManualResume() {
        repo.event("zhilian","r1","round_failed","登录失效");repo.event("job51","r2","round_failed","模型失败");repo.event("zhilian","r3","user_resume","已恢复");
        repo.events();repo.events();assertEquals(2,notices.size());assertTrue(notices.getFirst().getBoolean("paused"));
        repo.event("lagou","r4","round_expired","排队超时");assertFalse(notices.getLast().getBoolean("paused"));
    }
    @Test void quotaHasPlatformAndActiveRoundAndDoesNotGenerateDuplicateFailure() {
        var store=new PlatformStore(repo,"job51");String day=LocalDate.now(RoundDispatcher.ZONE).toString();
        store.updateSchedule(s->s.put("day",day).put("active",new JSONObject().put("id","slot-a").put("platform","job51")));
        store.platformQuotaReached("job51",day,"网站当日投递上限");store.platformQuotaReached("job51",day,"网站当日投递上限");
        store.finishScheduled("slot-a","网站当日投递上限",true,false,new JSONObject());
        assertEquals(1,notices.size());assertEquals("daily_limit",notices.getFirst().getString("type"));assertEquals("slot-a",notices.getFirst().getString("round"));
    }
    @Test void notificationLaunchFailureCannotErasePersistedEventOrTriggerRetry() {
        repo.onEvent(event->{throw new IllegalStateException("desktop unavailable");});
        assertDoesNotThrow(()->repo.event("boss","slot","round_failed","失败"));assertEquals(1,repo.events().length());
    }
    @Test void retryStartAndResultAreNotifiedWithAccuratePauseState() {
        repo.event("job51","slot|retry-1","retry_started","网络中断，重试一次");assertFalse(notices.getLast().getBoolean("paused"));
        repo.event("job51","slot|retry-1","retry_succeeded","重试完成");assertFalse(notices.getLast().getBoolean("paused"));
        repo.event("job51","slot|retry-1","retry_failed","仍然断网，暂停");assertTrue(notices.getLast().getBoolean("paused"));assertEquals(3,notices.size());
    }
}
