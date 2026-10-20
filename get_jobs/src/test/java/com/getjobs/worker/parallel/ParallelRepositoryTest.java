package com.getjobs.worker.parallel;

import org.json.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ParallelRepositoryTest {
    @TempDir Path directory;
    ParallelRepository repository;
    @BeforeEach void setup(){repository=new ParallelRepository(directory.resolve("state.db"));}
    @Test void simultaneousReservationSendsAtMostOnce() throws Exception {
        try(var pool=Executors.newFixedThreadPool(8)) {
            List<Callable<Boolean>> calls=new ArrayList<>();for(int i=0;i<20;i++)calls.add(()->repository.claim("boss","a","job","2026-10-19"));
            int count=0;for(Future<Boolean> r:pool.invokeAll(calls))if(r.get())count++;
            assertEquals(1,count);
        }
    }
    @Test void platformAndAccountHaveIndependentDailyReservations() {
        assertTrue(repository.claim("boss","a","same-job","2026-10-19"));
        assertTrue(repository.claim("shixiseng","a","same-job","2026-10-19"));
        assertTrue(repository.claim("boss","b","same-job","2026-10-19"));
        repository.finishClaim("boss","a","same-job","2026-10-19","complete");
        assertFalse(repository.claim("boss","a","same-job","2026-10-19"));
        assertTrue(repository.claim("boss","a","same-job","2026-10-20"));
    }
    @Test void ambiguousSendingStaysLockedAcrossDaysAndRestartUntilExplicitReconciliation() {
        repository.claim("job51","a","job","2026-10-19");repository.finishClaim("job51","a","job","2026-10-19","native_attempted");
        var restarted=new ParallelRepository(directory.resolve("state.db"));assertFalse(restarted.claim("job51","a","job","2026-10-20"));
        restarted.resolveUnsent("job51","job");assertTrue(restarted.claim("job51","a","job","2026-10-20"));
    }
    @Test void noExternalAttemptReleasesReservation() {
        repository.claim("lagou","a","job","2026-10-19");repository.finishClaim("lagou","a","job","2026-10-19","screened");
        assertTrue(repository.claim("lagou","a","job","2026-10-19"));
    }
    @Test void platformDocumentsAndHistoricalRoundSnapshotsCannotOverwriteEachOther() {
        repository.document("boss","settings.json",new JSONObject().put("maxSend",7));repository.document("shixiseng","settings.json",new JSONObject().put("maxSend",2));
        assertEquals(7,repository.document("boss","settings.json").getInt("maxSend"));
        var a=new JSONObject().put("roundId","round-a").put("status","screened");repository.document("boss","records.json",new JSONObject().put("job",a));
        repository.document("boss","records.json",new JSONObject().put("job",new JSONObject().put("roundId","round-b").put("status","complete")));
        assertEquals("screened",repository.candidates("boss","round-a").getJSONObject("job").getString("status"));
        assertEquals("complete",repository.candidates("boss","round-b").getJSONObject("job").getString("status"));
    }
    @Test void recordsAreCommittedBeforeRestartAndEventsKeepEachFailedRound() {
        repository.event("boss","r1","round_failed","failure1");repository.event("boss","r2","round_failed","failure2");
        assertEquals(2,new ParallelRepository(directory.resolve("state.db")).events().length());
    }
    @Test void verifiedCompletionUnlocksNextDayButRetainsSameDayDuplicateProtection() {
        repository.claim("boss","a","job","2026-10-19");repository.finishClaim("boss","a","job","2026-10-19","text_sent");
        repository.resolveConfirmed("boss","job");assertFalse(repository.claim("boss","a","job","2026-10-19"));assertTrue(repository.claim("boss","a","job","2026-10-20"));
    }
}
