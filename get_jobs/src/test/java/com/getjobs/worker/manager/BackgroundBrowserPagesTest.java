package com.getjobs.worker.manager;

import com.microsoft.playwright.*;
import org.junit.jupiter.api.*;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BackgroundBrowserPagesTest {
 @Test void backgroundTargetsRemainInsideTheirOwnAccountContextAndSupportForms() {
  String executable=System.getProperty("internship.test-browser");Assumptions.assumeTrue(executable!=null);
  try(var engine=Playwright.create();var browser=engine.chromium().launch(new BrowserType.LaunchOptions().setExecutablePath(Path.of(executable)).setHeadless(true))) {
   try(var first=browser.newContext();var second=browser.newContext()) {
    first.route("**/*",r->r.fulfill(new Route.FulfillOptions().setContentType("text/html").setBody("<input id=name>")));
    second.route("**/*",r->r.fulfill(new Route.FulfillOptions().setContentType("text/html").setBody("<h1>Other account</h1>")));
    first.addCookies(List.of(new com.microsoft.playwright.options.Cookie("fixture","first").setDomain("example.test").setPath("/")));
    first.newPage();second.newPage();
    var page=BackgroundBrowserPages.create(first);page.navigate("https://example.test/job");page.locator("#name").fill("safe");
    assertSame(first,page.context());assertEquals("safe",page.locator("#name").inputValue());assertEquals("first",first.cookies().getFirst().value);assertTrue(second.cookies().isEmpty());
    var other=BackgroundBrowserPages.create(second);other.navigate("https://example.test/job");assertEquals("Other account",other.locator("h1").innerText());assertEquals(2,first.pages().size());assertEquals(2,second.pages().size());
   }
  }
 }
}
