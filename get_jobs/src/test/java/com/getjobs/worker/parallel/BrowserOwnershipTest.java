package com.getjobs.worker.parallel;

import com.getjobs.worker.manager.PlaywrightManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class BrowserOwnershipTest {
    @TempDir Path directory;
    List<PlaywrightManager> owned=new ArrayList<>();
    PlaywrightManager fork(String p){var browser=new PlaywrightManager().isolated(p,directory.resolve(p),true,true);owned.add(browser);return browser;}
    @AfterEach void close() throws Exception {var f=PlaywrightManager.class.getDeclaredField("playwrightExecutor");f.setAccessible(true);for(var p:owned)((ExecutorService)f.get(p)).shutdownNow();}
    @Test void eachPlatformHasItsOwnOwnerThreadAndNestedCallsCannotBorrowAnotherOwner() {
        var a=fork("zhilian");var b=fork("job51");
        String ownerA=a.callOnPlaywright(()->Thread.currentThread().getName());
        String ownerB=a.callOnPlaywright(()->b.callOnPlaywright(()->Thread.currentThread().getName()));
        assertNotEquals(ownerA,ownerB);
    }
    @Test void aBlockedPlatformDoesNotBlockAnotherPlatform() throws Exception {
        var a=fork("zhilian");var b=fork("lagou");CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        try(var pool=Executors.newSingleThreadExecutor()) {
            var work=pool.submit(()->a.callOnPlaywright(()->{entered.countDown();release.await();return true;}));
            assertTrue(entered.await(2,TimeUnit.SECONDS));assertEquals("independent",b.callOnPlaywright(()->"independent"));release.countDown();assertTrue(work.get(2,TimeUnit.SECONDS));
        }finally{release.countDown();}
    }
    @Test void productionAccountsCannotBeOpenedAndProfilePathsAreDistinct() throws Exception {
        var boss=fork("boss");var nativeSite=fork("shixiseng");assertThrows(IllegalStateException.class,boss::init);assertThrows(IllegalStateException.class,nativeSite::init);
        var f=PlaywrightManager.class.getDeclaredField("USER_DATA_DIR");f.setAccessible(true);assertNotEquals(f.get(boss),f.get(nativeSite));assertTrue(f.get(boss).toString().startsWith(directory.toString()));
    }
}
