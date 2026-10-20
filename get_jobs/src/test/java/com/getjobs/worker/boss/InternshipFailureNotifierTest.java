package com.getjobs.worker.boss;
import org.json.JSONObject;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class InternshipFailureNotifierTest {
    @TempDir Path directory;
    InternshipStore store;InternshipFailureNotifier notifier;
    @BeforeEach void setup() {
        store=new InternshipStore(directory);notifier=mock(InternshipFailureNotifier.class);
        org.springframework.test.util.ReflectionTestUtils.setField(store,"failureNotifier",notifier);
        store.updateSchedule(s->s.put("day","2026-10-19").put("active",new JSONObject().put("id","2026-10-19T08:00")));
    }
    @Test void failureImmediatelyReportsOnceWithoutPolling() {
        store.finishScheduled("2026-10-19T08:00","图片发送未确认",true,false,new JSONObject());
        store.finishScheduled("2026-10-19T08:00","图片发送未确认",true,false,new JSONObject());
        verify(notifier,times(1)).report("round_failed","2026-10-19T08:00","BOSS直聘：图片发送未确认；该平台今日已暂停，另一平台继续运行");
        assertEquals("2026-10-19",store.scheduleState().getString("holdDate"));
    }
    @Test void quotaReportsOnceAndSuppressesSameRoundsFailureDuplicate() {
        store.scheduleQuotaReached("2026-10-19","今日投递次数已达上限");store.scheduleQuotaReached("2026-10-19","今日投递次数已达上限");
        store.finishScheduled("2026-10-19T08:00","今日投递次数已达上限",true,false,new JSONObject());
        verify(notifier).report("daily_limit","2026-10-19","今日投递次数已达上限；该平台今天已停止投递，另一平台不受影响");verifyNoMoreInteractions(notifier);
    }
    @Test void deadlineAndManualStopDoNotNotify() {
        store.finishScheduled("2026-10-19T08:00","17:00截止",true,true,new JSONObject());verifyNoInteractions(notifier);
        store.updateSchedule(s->s.put("active",new JSONObject().put("id","manual")));
        store.finishScheduled("manual","暂停请求已收到，将在下一次发送前停止",true,false,new JSONObject());verifyNoInteractions(notifier);
    }
    @Test void notifierPersistsEventAndLaunchesOnceWithoutBlockingOnDesktop() throws Exception {
        var real=spy(new InternshipFailureNotifier(directory,directory.resolve("fixture.ps1")));
        real.enabled=true;doNothing().when(real).launch(any());real.report("round_failed","08:00","测试失败");
        verify(real).launch(any());
        try(var files=Files.list(directory)) {Path event=files.filter(p->p.toString().endsWith(".json")).findFirst().orElseThrow();
            assertEquals("测试失败",new JSONObject(Files.readString(event)).getString("message"));}
    }
}
