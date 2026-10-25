package com.getjobs.worker.manager;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.Cookie;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HeadlessBrowserModeTest {
    @TempDir Path directory;
    @Test void switchingClosesTheOldOwnerBeforeReusingItsPersistentProfile() {
        var manager=new PlaywrightManager().isolated("zhilian",directory,true,false);
        var context=mock(BrowserContext.class);var engine=mock(Playwright.class);
        ReflectionTestUtils.setField(manager,"context",context);ReflectionTestUtils.setField(manager,"playwright",engine);
        manager.setHeadlessMode(false);
        var order=inOrder(context,engine);order.verify(context).close();order.verify(engine).close();
        assertFalse(manager.isInitialized());assertFalse(manager.isHeadlessMode());
        manager.setHeadlessMode(true);assertTrue(manager.isHeadlessMode());
    }
    @Test void explicitDisplaySwitchKeepsVerifiedSessionCookiesInMemoryWithoutLoggingOrWritingThem() {
        var manager=new PlaywrightManager().isolated("job51",directory,true,false);
        var context=mock(BrowserContext.class);Cookie session=new Cookie("fixture_session","private-fixture").setUrl("https://example.test");
        when(context.cookies()).thenReturn(List.of(session));ReflectionTestUtils.setField(manager,"context",context);
        manager.setHeadlessMode(false);
        assertEquals(List.of(session),ReflectionTestUtils.getField(manager,"displaySwitchCookies"));
        manager.setHeadlessMode(true);assertEquals(List.of(session),ReflectionTestUtils.getField(manager,"displaySwitchCookies"));
        assertFalse(Files.exists(directory.resolve("session.json")));
    }
    @Test void failedCloseDoesNotLaunchAnotherBrowserOrSilentlyChangeMode() {
        var manager=new PlaywrightManager().isolated("zhilian",directory,true,false);
        var context=mock(BrowserContext.class);ReflectionTestUtils.setField(manager,"context",context);
        doThrow(new RuntimeException("close failed")).when(context).close();
        assertThrows(RuntimeException.class,()->manager.setHeadlessMode(false));
        assertTrue(manager.isHeadlessMode());assertSame(context,ReflectionTestUtils.getField(manager,"context"));
    }
    @Test void launchFailureRemainsHeadlessAndReleasesPartialResources() {
        var base=new PlaywrightManager();ReflectionTestUtils.setField(base,"internshipBrowserExecutable",directory.resolve("missing-chrome.exe").toString());
        var manager=base.isolated("zhilian",directory.resolve("failed-profile"),true,false);
        RuntimeException failure=assertThrows(RuntimeException.class,manager::init);
        assertTrue(failure.getMessage().contains("未切换普通Chrome"));assertTrue(manager.isHeadlessMode());
        assertFalse(manager.isInitialized());assertNull(ReflectionTestUtils.getField(manager,"playwright"));
    }
    @Test void invisiblePersistentChromeCanKeepLoginOpenTabsAndUploadWithoutNativeDialogs() throws Exception {
        String executable=System.getProperty("internship.test-browser");
        Assumptions.assumeTrue(executable!=null&&Files.isRegularFile(Path.of(executable)),"Provide local Chrome for acceptance");
        var base=new PlaywrightManager();ReflectionTestUtils.setField(base,"internshipBrowserExecutable",executable);
        var manager=base.isolated("zhilian",directory.resolve("profile"),true,false);
        assertTrue(manager.isHeadlessMode());var options=manager.browserLaunchOptions();assertEquals(Boolean.TRUE,options.headless);
        assertFalse(options.args.contains("--start-maximized"));
        try(var engine=Playwright.create()) {
            var context=engine.chromium().launchPersistentContext(directory.resolve("profile"),options);
            context.route("**/*",route->route.fulfill(new Route.FulfillOptions().setContentType("text/html").setBody("<input type=file id=upload><div id=result></div>")));
            context.addCookies(List.of(new Cookie("fixture_login","fixture-only").setUrl("https://example.test").setExpires(System.currentTimeMillis()/1000.0+3600)));
            var page=context.newPage();page.navigate("https://example.test/job");page.bringToFront();
            Path image=directory.resolve("fixture.png");Files.write(image,Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jX1sAAAAASUVORK5CYII="));
            page.locator("#upload").setInputFiles(image);
            assertEquals("fixture.png",page.locator("#upload").evaluate("e=>e.files[0].name"));
            var popup=page.waitForPopup(()->page.evaluate("window.open('https://example.test/detail')"));
            assertTrue(popup.url().contains("example.test"));context.close();
            context=engine.chromium().launchPersistentContext(directory.resolve("profile"),options);
            assertTrue(context.cookies().stream().anyMatch(c->c.name.equals("fixture_login")&&c.value.equals("fixture-only")));
            context.close();
        }
    }
}
