package com.getjobs.worker.parallel;

import com.getjobs.worker.boss.*;
import com.getjobs.worker.manager.PlaywrightManager;
import org.json.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ParallelWorkbenchTest {
    @TempDir Path directory;
    ParallelWorkbench service;
    ParallelRepository repo;
    Map<String,InternshipRunner> forks;
    @BeforeEach void setup(){
        repo=new ParallelRepository(directory.resolve("test.db"));repo.document("_global","profile",InternshipPolicy.defaults().put("confirmed",true).put("resumeText","用户研究和PRD").put("targetDirections","库存分析实习").put("keywords",new JSONArray().put("库存分析实习")));
        var base=mock(InternshipRunner.class);var browser=mock(PlaywrightManager.class);var model=mock(InternshipModel.class);forks=new HashMap<>();when(model.status()).thenReturn(Map.of("model","gpt-6-luna"));
        when(browser.isolated(anyString(),any(),anyBoolean(),anyBoolean())).thenAnswer(call->mock(PlaywrightManager.class));
        when(base.isolated(any(),any(),any(),anyBoolean(),anyBoolean(),anyBoolean(),any(),any())).thenAnswer(call->{String id=((PlatformStore)call.getArgument(0)).settings().optString("platform");var fork=mock(InternshipRunner.class);when(fork.status()).thenReturn(Map.of("progress","测试就绪"));return fork;});
        service=new ParallelWorkbench(repo,base,browser,model);ReflectionTestUtils.setField(service,"enabled",true);ReflectionTestUtils.setField(service,"protect",true);ReflectionTestUtils.setField(service,"deadline","2026-10-20T07:00:00+08:00");service.clock=Clock.fixed(Instant.parse("2026-10-19T16:00:00Z"),ZoneOffset.UTC);service.initialize();
        for(String id:Platforms.IDS)forks.put(id,service.worker(id).runner);
    }
    @Test void productionAccountsAndResumeAcceptanceCannotBeUnlockedByOrdinarySettings(){
        assertThrows(IllegalStateException.class,()->service.start("boss","recommend"));assertThrows(IllegalArgumentException.class,()->service.saveConfig("job51",new JSONObject().put("liveSubmissionVerified",true)));
        service.saveConfig("job51",new JSONObject().put("resumeReady",true).put("sendEnabled",true));assertEquals("scan",service.worker("job51").effective().getString("mode"));
        assertThrows(IllegalStateException.class,()->service.deliver("job51","job",true));verify(forks.get("job51"),never()).deliver(anyString());
    }
    @Test void replacementDoesNotInherit51jobResumeOrAcceptance(){
        assertFalse(service.worker("job51").config().getBoolean("resumeReady"));assertEquals(InternshipYingjiesheng.ADAPTER,service.worker("job51").config().getString("siteAdapter"));
        service.worker("job51").store.updateSchedule(s->s.put("nativeAcceptance",new JSONObject().put("liveSubmissionVerified",true).put("source","job51-application-history")));
        assertFalse(service.worker("job51").liveVerified());assertEquals("应届生实习",Platforms.label("job51"));assertEquals("https://q.yingjiesheng.com/pc/searchintention",Platforms.home("job51"));
        assertNotEquals(InternshipPolicy.fingerprint(service.profile()),InternshipPolicy.fingerprint(service.worker("job51").effective()));
    }
    @Test void deadlineDisallowsAnyNewDevelopmentOperation(){
        service.requireWindow();service.clock=Clock.fixed(Instant.parse("2026-10-19T23:00:00Z"),ZoneOffset.UTC);
        assertThrows(IllegalStateException.class,()->service.requireWindow());assertThrows(IllegalStateException.class,()->service.start("zhilian","search"));assertThrows(IllegalStateException.class,()->service.saveProfile(new JSONObject().put("excludedDirections","新条件")));assertTrue((Boolean)service.status().get("deadlineStopped"));
    }
    @Test void profileChangesAffectNextSnapshotAndEachPlatformKeepsItsOwnLimits(){
        service.saveConfig("zhilian",new JSONObject().put("maxScan",8).put("maxSend",1));service.saveConfig("lagou",new JSONObject().put("maxScan",12).put("maxSend",3));
        JSONObject first=service.worker("zhilian").effective();service.saveProfile(new JSONObject().put("excludedDirections","排除要求Python开发的岗位"));
        JSONObject next=service.worker("zhilian").effective();assertNotEquals(first.getString("excludedDirections"),next.getString("excludedDirections"));assertEquals(8,next.getInt("maxScan"));assertEquals(12,service.worker("lagou").effective().getInt("maxScan"));assertEquals(3,service.worker("lagou").effective().getInt("maxSend"));
        assertThrows(IllegalArgumentException.class,()->service.saveConfig("zhilian",new JSONObject().put("maxSend",5000)));
    }
    @Test void explicitlyResumedWindowWithoutDeadlineDoesNotReusePreviousSevenCutoff(){
        ReflectionTestUtils.setField(service,"deadline","");service.clock=Clock.fixed(Instant.parse("2026-10-20T01:45:00Z"),ZoneOffset.UTC);
        assertDoesNotThrow(service::requireWindow);assertDoesNotThrow(service::tick);assertEquals(false,service.status().get("deadlineStopped"));assertEquals(false,service.status().get("sendingEnabled"));
    }
    @Test void abandonedPlatformCannotBeReenabledOrUsedButItsHistoryRemainsStored(){
        repo.document("lagou","historical",new JSONObject().put("retained",true));
        assertThrows(IllegalStateException.class,()->service.saveConfig("lagou",new JSONObject().put("enabled",true)));
        assertThrows(IllegalStateException.class,()->service.start("lagou","search"));assertThrows(IllegalStateException.class,()->service.checkSession("lagou"));assertThrows(IllegalStateException.class,()->service.deliver("lagou","old",true));
        assertEquals(4,((List<?>)service.status().get("platforms")).size());assertTrue(repo.document("lagou","historical").getBoolean("retained"));
    }
    @Test void manualBrowserBlocksRoundsUntilExplicitReturnWithoutClearingExistingHolds(){
        var worker=service.worker("zhilian");ReflectionTestUtils.setField(service,"live",true);
        ReflectionTestUtils.setField(worker,"live",true);
        ReflectionTestUtils.setField(worker,"manualBrowser",true);
        assertTrue(worker.blocked("2026-10-20").contains("人工处理"));
        assertThrows(IllegalStateException.class,()->worker.start(new JSONObject().put("source","recommend")));
        verify(worker.browser,never()).setHeadlessMode(true);
        ReflectionTestUtils.setField(worker,"manualBrowser",false);
        JSONObject c=worker.config().put("holdDate","2026-10-20").put("holdReason","existing model failure");repo.document("zhilian","config",c);
        assertEquals("existing model failure",worker.blocked("2026-10-20"));
    }
    @Test void displayingBossForManualLoginDoesNotNavigateOrInspectItsPage() {
        var worker=service.worker("boss");ReflectionTestUtils.setField(worker,"live",true);ReflectionTestUtils.setField(worker,"protect",false);
        var page=mock(com.microsoft.playwright.Page.class);when(worker.browser.getBossPage()).thenReturn(page);
        doAnswer(call->{java.util.concurrent.Callable<?> action=call.getArgument(0);return action.call();}).when(worker.browser).callOnPlaywright(any());
        worker.reserveControl();var result=worker.checkReservedSession(true);
        assertTrue(String.valueOf(result.get("message")).contains("未刷新"));verify(page).bringToFront();verifyNoMoreInteractions(page);
        verify(worker.browser,never()).checkAndSaveBossSession();assertTrue(worker.blocked("2026-10-20").contains("人工处理"));
    }
    @Test void onlyAuthorizedPlatformCanUseBackgroundChrome() {
        for(String id:List.of("boss","job51","shixiseng","zhilian")){service.saveConfig(id,new JSONObject().put("browserMode","background"));assertTrue(service.worker(id).backgroundBrowser());}
        assertThrows(IllegalArgumentException.class,()->service.saveConfig("lagou",new JSONObject().put("browserMode","background")));
        service.saveConfig("job51",new JSONObject().put("browserMode","background"));
        assertTrue(service.worker("job51").backgroundBrowser());assertTrue(service.worker("shixiseng").backgroundBrowser());
        service.saveConfig("boss",new JSONObject().put("browserMode","background"));assertTrue(service.worker("boss").backgroundBrowser());
        assertEquals("background",service.worker("job51").status().get("browserMode"));
        assertThrows(IllegalArgumentException.class,()->service.saveConfig("job51",new JSONObject().put("browserMode","invalid")));
    }
    @Test void processShutdownPreservesSavedScheduleButExplicitPauseDisablesIt(){service.enableSchedule(true);service.close();assertTrue(repo.document("_global","dispatch").getBoolean("enabled"));service.pauseAll();assertFalse(repo.document("_global","dispatch").getBoolean("enabled"));}
}
