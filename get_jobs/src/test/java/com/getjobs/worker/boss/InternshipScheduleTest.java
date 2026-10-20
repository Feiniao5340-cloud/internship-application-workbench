package com.getjobs.worker.boss;

import org.json.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InternshipScheduleTest {
    @TempDir Path directory;
    InternshipStore store;
    InternshipRunner runner;
    InternshipSchedule schedule;
    @BeforeEach void init() {
        store=new InternshipStore(directory);runner=mock(InternshipRunner.class);
        schedule=new InternshipSchedule(store,runner);
        store.saveSettings(InternshipPolicy.defaults().put("confirmed",true).put("greeting","你好").put("resumeText","产品经历").put("targetDirections","库存分析实习")
                .put("keywords",new JSONArray().put("库存分析实习")));
        time("2026-10-19T06:00:00");schedule.setEnabled(true);
    }
    void time(String local) {schedule.clock=Clock.fixed(LocalDateTime.parse(local).atZone(InternshipSchedule.ZONE).toInstant(),ZoneOffset.UTC);}
    @Test void activationWaitsForSevenAndDoesNotSendImmediately() {
        schedule.tick();verify(runner,never()).startScheduled(anyString(),anyString(),anyString(),any());
        assertEquals("2026-10-18T23:00:00Z",store.scheduleState().getString("notBefore"));
        time("2026-10-19T07:00:00");schedule.tick();schedule.tick();
        verify(runner,times(1)).startScheduled(eq("boss"),eq("recommend"),eq("2026-10-19T07:00"),eq(Instant.parse("2026-10-18T23:15:00Z")));
    }
    @Test void halfHourSearchQueuesWhileBusyAndDrainsOnce() {
        time("2026-10-19T07:30:00");when(runner.isRunning()).thenReturn(true);schedule.tick();schedule.tick();
        assertEquals(1,store.scheduleState().getJSONArray("pending").length());
        when(runner.isRunning()).thenReturn(false);schedule.tick();schedule.tick();
        verify(runner,times(1)).startScheduled(eq("boss"),eq("search"),anyString(),any());
    }
    @Test void deadlineDiscardsPendingAndNeverStartsNewRound() {
        time("2026-10-19T16:30:00");when(runner.isRunning()).thenReturn(true);schedule.tick();
        time("2026-10-19T17:00:00");when(runner.isRunning()).thenReturn(false);schedule.tick();
        assertTrue(store.scheduleState().getJSONArray("pending").isEmpty());
        verify(runner,never()).startScheduled(anyString(),anyString(),anyString(),any());
    }
    @Test void quotaAndFailureHoldBlockRestOfDayButNotNextDay() {
        store.scheduleQuotaReached("2026-10-19","上限");time("2026-10-19T08:00:00");schedule.tick();
        verify(runner,never()).startScheduled(anyString(),anyString(),anyString(),any());
        time("2026-10-20T07:00:00");schedule.tick();verify(runner).startScheduled(eq("boss"),eq("recommend"),anyString(),any());
    }
    @Test void unexpectedRestartHoldsToday() {
        store.updateSchedule(s->s.put("day","2026-10-19").put("active",new JSONObject().put("id","old")));
        time("2026-10-19T07:00:00");schedule.tick();
        assertEquals("2026-10-19",store.scheduleState().getString("holdDate"));
        verify(runner,never()).startScheduled(anyString(),anyString(),anyString(),any());
    }
    @Test void enablingAfterSevenWaitsUntilNextDayAndDisableCancelsQueue() {
        time("2026-10-19T08:00:00");schedule.setEnabled(true);schedule.tick();
        assertEquals("2026-10-19T23:00:00Z",store.scheduleState().getString("notBefore"));
        schedule.setEnabled(false);assertFalse(store.scheduleState().getBoolean("enabled"));
        verify(runner,never()).startScheduled(anyString(),anyString(),anyString(),any());
    }
    @Test void runnerEnforcesDeadlineButLeavesManualRunAlone() throws Exception {
        var real=new InternshipRunner(store,null,null,null,null,null);
        real.clock=Clock.fixed(Instant.parse("2026-10-19T09:00:00Z"),ZoneOffset.UTC);
        var field=InternshipRunner.class.getDeclaredField("scheduledDeadline");field.setAccessible(true);
        assertFalse(real.shouldStop());field.set(real,Instant.parse("2026-10-19T09:00:00Z"));assertTrue(real.shouldStop());
    }
    @Test void fourSlotsAlternatePlatformsAndSources() {
        store.saveShixisengState(new JSONObject().put("liveSubmissionVerified",true));
        for(String minute:new String[]{"00","15","30","45"}) {time("2026-10-19T07:"+minute+":00");schedule.tick();}
        verify(runner).startScheduled(eq("shixiseng"),eq("recommend"),eq("2026-10-19T07:15"),eq(Instant.parse("2026-10-18T23:30:00Z")));
        verify(runner).startScheduled(eq("shixiseng"),eq("search"),eq("2026-10-19T07:45"),eq(Instant.parse("2026-10-19T00:00:00Z")));
        verify(runner).startScheduled(eq("boss"),eq("search"),anyString(),any());
    }
    @Test void expiredBacklogIsSkippedAndOtherPlatformStillStarts() {
        store.saveShixisengState(new JSONObject().put("liveSubmissionVerified",true));
        time("2026-10-19T07:00:00");when(runner.isRunning()).thenReturn(true);schedule.tick();
        time("2026-10-19T07:15:00");when(runner.isRunning()).thenReturn(false);schedule.tick();
        verify(runner,never()).startScheduled(eq("boss"),anyString(),anyString(),any());
        verify(runner).startScheduled(eq("shixiseng"),eq("recommend"),anyString(),any());
        assertEquals("skipped",store.scheduleState().getJSONArray("completed").getJSONObject(0).getString("status"));
    }
    @Test void bossHoldDoesNotBlockShixisengAndNativeRequiresLiveAcceptance() {
        store.holdSchedule("2026-10-19","图片未确认");time("2026-10-19T07:15:00");schedule.tick();
        verifyNoMoreInteractionsExceptStatus();
        store.saveShixisengState(new JSONObject().put("liveSubmissionVerified",true));
        time("2026-10-19T07:45:00");schedule.tick();
        verify(runner).startScheduled(eq("shixiseng"),eq("search"),anyString(),any());
    }
    void verifyNoMoreInteractionsExceptStatus() {verify(runner,never()).startScheduled(anyString(),anyString(),anyString(),any());}
    @Test void budgetSeparatesCollectionSendCutoffAndInFlightConfirmation() throws Exception {
        var real=new InternshipRunner(store,null,null,null,null,null);
        var deadline=InternshipRunner.class.getDeclaredField("scheduledDeadline");deadline.setAccessible(true);deadline.set(real,Instant.parse("2026-10-19T00:15:00Z"));
        real.clock=Clock.fixed(Instant.parse("2026-10-19T00:10:00Z"),ZoneOffset.UTC);
        assertTrue(real.collectionEnded());assertFalse(real.sendWindowEnded());assertFalse(real.shouldStop());
        real.clock=Clock.fixed(Instant.parse("2026-10-19T00:14:00Z"),ZoneOffset.UTC);assertTrue(real.sendWindowEnded());
        var inFlight=InternshipRunner.class.getDeclaredField("deliveryInFlight");inFlight.setAccessible(true);inFlight.set(real,true);
        real.clock=Clock.fixed(Instant.parse("2026-10-19T00:16:00Z"),ZoneOffset.UTC);assertFalse(real.shouldStop());
        inFlight.set(real,false);assertTrue(real.shouldStop());
    }
    @Test void nativeFailureAndQuotaNeverHoldBoss() {
        store.updateSchedule(s->s.put("day","2026-10-19").put("active",new JSONObject().put("id","2026-10-19T07:15").put("platform","shixiseng")));
        store.finishScheduled("2026-10-19T07:15","站内简历提交未确认",true,false,new JSONObject());
        assertEquals("2026-10-19",store.scheduleState().getJSONObject("platforms").getJSONObject("shixiseng").getString("holdDate"));
        assertFalse(store.scheduleState().getJSONObject("platforms").getJSONObject("boss").has("holdDate"));
        store.platformQuotaReached("shixiseng","2026-10-19","达到当日投递上限");
        time("2026-10-19T07:30:00");schedule.tick();verify(runner).startScheduled(eq("boss"),eq("search"),anyString(),any());
        time("2026-10-19T07:45:00");schedule.tick();verify(runner,never()).startScheduled(eq("shixiseng"),anyString(),anyString(),any());
    }
    @Test void legacyBossQuotaMigratesWithoutBlockingNative() throws Exception {
        java.nio.file.Files.writeString(directory.resolve("schedule.json"),new JSONObject().put("enabled",true).put("day","2026-10-19")
            .put("notBefore","2026-10-18T23:00:00Z").put("quotaDate","2026-10-19").toString());
        store.saveShixisengState(new JSONObject().put("liveSubmissionVerified",true));time("2026-10-19T07:15:00");schedule.tick();
        verify(runner).startScheduled(eq("shixiseng"),eq("recommend"),anyString(),any());
    }
    @Test void platformQuotaDetectionDoesNotTreatSalaryAsQuota() {
        assertTrue(InternshipPolicy.dailyCommunicationLimit("今日沟通人数已达上限，明天再来"));
        assertTrue(InternshipPolicy.dailyCommunicationLimit("今天打招呼次数已用完"));
        assertTrue(InternshipPolicy.dailyCommunicationLimit("今日投递次数已达上限"));
        assertTrue(InternshipPolicy.dailyCommunicationLimit("私信次数已用完，明天再来"));
        assertFalse(InternshipPolicy.dailyCommunicationLimit("今日推荐，日薪上限300元"));
        assertTrue(InternshipPolicy.dailyCommunicationLimit("今日\n投递次数\n已达上限"));
        assertTrue(InternshipPolicy.dailyCommunicationLimit("您今日沟通额度已用尽，请明天再来"));
        assertFalse(InternshipPolicy.dailyCommunicationLimit("立即投递\n职位描述\n数据分析师\n客户公司人数\n20-99人\n公司介绍\n致力达到国际领先水准"));
        assertFalse(InternshipPolicy.dailyCommunicationLimit("今日推荐\n立即投递\n薪资上限300元"));
        assertFalse(InternshipPolicy.dailyCommunicationLimit("岗位投递人数达到200人"));
    }
}
