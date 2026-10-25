package com.getjobs.worker.boss;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
class InternshipModelDiagnosticsTest {
 @TempDir Path directory;
 @Test void classifiesActualFailuresWithoutExposingPrivateErrorText() {
  assertTrue(InternshipModel.processFailure("HTTP 429 usage limit reached secret-user@example.test",1).contains("额度"));
  assertTrue(InternshipModel.processFailure("401 Unauthorized token expired private-token",1).contains("登录失效"));
  assertTrue(InternshipModel.processFailure("stream disconnected TLS unexpected EOF private-resume",1).contains("网络"));
  assertFalse(InternshipModel.processFailure("some error private-resume secret-user@example.test",7).contains("private-resume"));
  assertTrue(InternshipModel.processFailure("some error",7).contains("退出码7"));
 }
 @Test void diagnosticsReadOnlyTheBoundedTail() throws Exception {
  Path file=directory.resolve("stderr.txt");Files.writeString(file,"x".repeat(20000)+"EOF");
  String tail=InternshipModel.readErrorTail(file);assertEquals(16384,tail.length());assertTrue(tail.endsWith("EOF"));
 }
}
