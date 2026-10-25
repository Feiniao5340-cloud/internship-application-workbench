package com.getjobs.worker.manager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import com.microsoft.playwright.*;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

class BackgroundChromeWindowsTest {
 @TempDir Path directory;
 static class FakeWindows implements BackgroundChromeWindows.Windows {
  Map<Long,Integer> owners=new LinkedHashMap<>(Map.of(1L,100,2L,200,3L,100));
  Set<Long> visible=new HashSet<>(owners.keySet());List<Long> hidden=new ArrayList<>(),shown=new ArrayList<>();long active=1;
  public Map<Long,Integer> windows(){return owners;}
  public long foreground(){return active;}
  public boolean visible(long h){return visible.contains(h);}
  public void hide(long h){hidden.add(h);visible.remove(h);}
  public void show(long h){shown.add(h);visible.add(h);}
  public void foreground(long h){active=h;}
 }
 @Test void hidesOnlyOwnedWindowsIncludingNewPopupsAndNeverTouchesPersonalChrome() {
  var windows=new FakeWindows();try(var owner=new BackgroundChromeWindows(100,windows,2)) {
   owner.tick();assertEquals(Set.of(1L,3L),new HashSet<>(windows.hidden));assertTrue(windows.visible.contains(2L));assertEquals(2,windows.active);
   windows.owners.put(4L,100);windows.visible.add(4L);owner.tick();assertTrue(windows.hidden.contains(4L));
   owner.showAndClose();assertEquals(Set.of(1L,3L,4L),new HashSet<>(windows.shown));assertFalse(windows.shown.contains(2L));
  }
 }
 @Test void manualDisplayNeverRevealsChromesOriginallyHiddenHelperWindows() {
  var windows=new FakeWindows();windows.owners.put(5L,100);
  try(var owner=new BackgroundChromeWindows(100,windows,2)) {
   owner.tick();owner.showAndClose();
   assertEquals(Set.of(1L,3L),new HashSet<>(windows.shown));assertFalse(windows.shown.contains(5L));
  }
 }
 @Test void simultaneousPlatformsNeverRestoreFocusToAnotherAutomationBrowser() {
  var windows=new FakeWindows();windows.owners.put(4L,300);windows.visible.add(4L);
  try(var first=new BackgroundChromeWindows(100,windows,4);var second=new BackgroundChromeWindows(200,windows,4)) {
   windows.active=2;first.tick();windows.active=1;first.tick();assertEquals(4,windows.active);
   windows.active=1;second.tick();windows.active=2;second.tick();assertEquals(4,windows.active);
  }
 }
 @Test void deadBrowserCannotControlWindowsAfterItsProcessIdIsReused() {
  var windows=new FakeWindows();var alive=new java.util.concurrent.atomic.AtomicBoolean(true);
  try(var owner=new BackgroundChromeWindows(100,windows,2,alive::get)) {
   owner.tick();int hidden=windows.hidden.size();alive.set(false);windows.visible.add(1L);owner.tick();
   assertEquals(hidden,windows.hidden.size());assertTrue(windows.visible.contains(1L));assertThrows(IllegalStateException.class,owner::ensureHealthy);
  }
 }
 @Test void windowControlFailureStopsAutomaticUseRatherThanExposingAnUncontrolledBrowser() {
  var windows=new FakeWindows(){@Override public Map<Long,Integer> windows(){throw new IllegalStateException("native failure");}};
  try(var owner=new BackgroundChromeWindows(100,windows,0)){owner.tick();assertThrows(IllegalStateException.class,owner::ensureHealthy);}
 }
 @Test void realHeadedChromeRemainsHiddenThroughNavigationAndNewWindows() throws Exception {
  String executable=System.getProperty("internship.test-browser");Assumptions.assumeTrue(executable!=null&&System.getProperty("os.name").startsWith("Windows"));
  var manager=new PlaywrightManager();ReflectionTestUtils.setField(manager,"internshipBrowserExecutable",executable);
  manager.setBackgroundMode(true);var options=manager.browserLaunchOptions();assertFalse(options.headless);assertTrue(options.args.contains("--window-position=-32000,-32000"));
  try(var engine=Playwright.create()) {
   var context=engine.chromium().launchPersistentContext(directory.resolve("profile"),options);
   try(var owner=BackgroundChromeWindows.start(context,BackgroundChromeWindows.currentForeground())) {
    context.route("**/*",r->r.fulfill(new Route.FulfillOptions().setContentType("text/html").setBody("<input type=file id=upload><h1>Fixture</h1>")));
    var page=BackgroundBrowserPages.create(context);page.navigate("https://example.test/job");
    var popup=page.waitForPopup(()->page.evaluate("window.open('https://example.test/new-window','fixture','width=800,height=600')"));
    popup.bringToFront();Thread.sleep(800);owner.ensureHealthy();
    var actual=new BackgroundChromeWindows.NativeWindows();var field=BackgroundChromeWindows.class.getDeclaredField("browserPid");field.setAccessible(true);int pid=(int)field.get(owner);
    assertTrue(actual.windows().entrySet().stream().filter(e->e.getValue()==pid).noneMatch(e->actual.visible(e.getKey())));
    Path resume=directory.resolve("fixture.txt");Files.writeString(resume,"fixture");page.locator("#upload").setInputFiles(resume);
    assertEquals("fixture.txt",page.locator("#upload").evaluate("e=>e.files[0].name"));
   }finally{context.close();}
  }
 }
}
