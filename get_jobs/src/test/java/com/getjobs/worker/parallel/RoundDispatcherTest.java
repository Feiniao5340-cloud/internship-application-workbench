package com.getjobs.worker.parallel;

import org.json.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RoundDispatcherTest {
    @TempDir Path directory;
    ParallelRepository repo;
    RoundDispatcher dispatcher;
    Map<String,Fake> workers;
    static class Fake implements RoundDispatcher.Worker {
        boolean busy,failStart;String blocked="";List<JSONObject> starts=new ArrayList<>();
        public boolean running(){return busy;}public String blocked(String day){return blocked;}
        public void start(JSONObject job){if(failStart)throw new IllegalStateException("该平台登录失效");busy=true;starts.add(new JSONObject(job.toString()));}
        public void stop(){busy=false;}public JSONObject outcome(JSONObject job){return new JSONObject().put("failed",false);}
    }
    @BeforeEach void setup(){repo=new ParallelRepository(directory.resolve("s.db"));workers=new LinkedHashMap<>();Map<String,RoundDispatcher.Worker> generic=new LinkedHashMap<>();for(String p:Platforms.IDS){Fake w=new Fake();workers.put(p,w);generic.put(p,w);}dispatcher=new RoundDispatcher(repo,generic,p->true);time("2026-10-20T07:00:00");dispatcher.setEnabled(true);}
    void time(String value){dispatcher.clock=Clock.fixed(LocalDateTime.parse(value).atZone(RoundDispatcher.ZONE).toInstant(),ZoneOffset.UTC);}
    @Test void fivePlatformsRunThreeAtOnceAndDrainWithoutOverlappingOnePlatform() {
        dispatcher.tick();assertEquals(3,dispatcher.state().getJSONObject("active").length());assertEquals(2,dispatcher.state().getJSONArray("pending").length());
        dispatcher.tick();assertEquals(3,workers.values().stream().filter(w->w.busy).count());
        workers.get("boss").busy=false;dispatcher.tick();assertTrue(workers.get("job51").busy);assertEquals(3,dispatcher.state().getJSONObject("active").length());
        workers.get("shixiseng").busy=false;dispatcher.tick();assertTrue(workers.get("lagou").busy);assertEquals(0,dispatcher.state().getJSONArray("pending").length());
    }
    @Test void halfHourChangesSourceAndRotatesLaunchOrder() {
        dispatcher.tick();workers.values().forEach(w->w.busy=false);dispatcher.tick();workers.values().forEach(w->w.busy=false);dispatcher.tick();
        time("2026-10-20T07:30:00");dispatcher.tick();
        assertEquals("search",dispatcher.state().getJSONObject("active").getJSONObject("shixiseng").getString("source"));
        assertFalse(dispatcher.state().getJSONObject("active").has("boss"));
    }
    @Test void onePlatformFailureOrQuotaNeverBlocksOthers() {
        workers.get("boss").failStart=true;workers.get("shixiseng").blocked="今日上限";dispatcher.tick();
        assertTrue(workers.get("zhilian").busy);assertTrue(workers.get("job51").busy);assertTrue(workers.get("lagou").busy);
        assertEquals(1,repo.events().length());assertEquals("2026-10-20",repo.document("boss","config").getString("holdDate"));
    }
    @Test void expiredQueuedTasksAreReportedAndNeverReplayed() {
        dispatcher.tick();time("2026-10-20T07:30:00");dispatcher.tick();
        int expired=0;for(Object item:dispatcher.state().getJSONArray("completed"))if(((JSONObject)item).optString("status").equals("expired"))expired++;
        assertEquals(2,expired);assertEquals(2,repo.events().length());
    }
    @Test void dailyCutoffDoesNotNotifyAsFailureAndDoesNotStartSeventeenSlot() {
        time("2026-10-20T16:30:00");dispatcher.tick();time("2026-10-20T17:00:00");dispatcher.tick();
        assertTrue(dispatcher.state().getJSONArray("pending").isEmpty());assertTrue(repo.events().isEmpty());
        assertEquals(5,dispatcher.state().getJSONArray("scheduledSlots").length());
    }
    @Test void manualReadOnlyValidationWorksDuringNightWindowEvenWithAutomaticScheduleOff() {
        dispatcher.setEnabled(false);time("2026-10-19T23:30:00");dispatcher.enqueueManual("zhilian","search");dispatcher.tick();
        assertTrue(workers.get("zhilian").busy);assertEquals(1,dispatcher.state().getJSONObject("active").length());
    }
    @Test void unexpectedRestartRecordsInterruptedRoundAndDoesNotRepeatIt() {
        dispatcher.tick();var restarted=new RoundDispatcher(repo,new LinkedHashMap<>(workers),p->false);
        workers.values().forEach(w->w.busy=false);restarted.clock=dispatcher.clock;restarted.setEnabled(false);restarted.tick();
        assertTrue(restarted.state().getJSONObject("active").isEmpty());assertEquals(3,repo.events().length());
    }
    @Test void platformPauseRemovesOnlyItsWaitingTask() {
        dispatcher.tick();dispatcher.pausePlatform("lagou");assertEquals(1,dispatcher.state().getJSONArray("pending").length());assertTrue(workers.get("boss").busy);
    }
    @Test void workersReceiveFourteenMinuteSlicesSoSecondWaveHasTimeBeforeHalfHour() {
        dispatcher.tick();assertEquals("2026-10-19T23:14:00Z",dispatcher.state().getJSONObject("active").getJSONObject("boss").getString("deadline"));
        time("2026-10-20T07:14:00");workers.values().forEach(w->w.busy=false);dispatcher.tick();
        assertEquals("2026-10-19T23:28:00Z",dispatcher.state().getJSONObject("active").getJSONObject("lagou").getString("deadline"));
        assertEquals(2,dispatcher.state().getJSONObject("active").length());
    }
    @Test void manualControlsSharePlatformCapacityAndCannotRaceQueuedRound() {
        dispatcher.tick();assertThrows(IllegalStateException.class,()->dispatcher.control("lagou",()->workers.get("lagou").busy=true));
        dispatcher.pausePlatform("lagou");assertThrows(IllegalStateException.class,()->dispatcher.control("lagou",()->workers.get("lagou").busy=true));
        workers.get("boss").busy=false;dispatcher.tick();assertEquals(3,dispatcher.state().getJSONObject("active").length());
        workers.get("zhilian").busy=false;dispatcher.tick();dispatcher.control("lagou",()->workers.get("lagou").busy=true);
        assertEquals(3,workers.values().stream().filter(w->w.busy).count());assertThrows(IllegalStateException.class,()->dispatcher.control("lagou",()->{}));
    }
    @Test void disablingAPlatformAfterItQueuedPreventsItsNextStart() {
        Set<String> enabled=new HashSet<>(Platforms.IDS);dispatcher=new RoundDispatcher(repo,new LinkedHashMap<>(workers),enabled::contains);time("2026-10-20T07:00:00");dispatcher.setEnabled(true);dispatcher.tick();
        enabled.remove("lagou");workers.get("boss").busy=false;dispatcher.tick();workers.get("shixiseng").busy=false;dispatcher.tick();
        assertTrue(workers.get("lagou").starts.isEmpty());assertTrue(dispatcher.state().getJSONArray("pending").isEmpty());assertTrue(repo.events().isEmpty());
    }
}
