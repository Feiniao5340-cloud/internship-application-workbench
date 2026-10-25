package com.getjobs.worker.boss;
import com.getjobs.application.service.AiService;
import org.json.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InternshipEvidenceTest {
    @TempDir Path directory;
    InternshipFlowTest fixture=new InternshipFlowTest();
    @Test void evidenceIdsRestoreExactPunctuationWithoutWeakeningQuoteValidation() {
        var c=fixture.profile();var j=fixture.job().put("jd","负责需求分析；\n负责PRD编写。实习生。");
        var answer=fixture.answer("review").put("jdEvidence",new JSONArray().put("jd:0001")).put("resumeEvidence",new JSONArray().put("resume:0001"));
        var r=InternshipPolicy.evaluate(c,j,answer.toString());
        assertFalse(r.optBoolean("pendingModel"));assertEquals("负责需求分析；",r.getJSONArray("jdEvidence").getString(0));
        assertTrue(c.getString("resumeText").contains(r.getJSONArray("resumeEvidence").getString(0)));
        assertTrue(r.getBoolean("evidenceResolvedById"));
        answer.put("jdEvidence",new JSONArray().put("负责需求分析。"));
        assertTrue(InternshipPolicy.evaluate(c,j,answer.toString()).getBoolean("pendingModel"));
    }
    @Test void fabricatedAndCrossSourceIdsStayBlocked() {
        var c=fixture.profile();var j=fixture.job();
        for(String invalid:new String[]{"jd:9999","resume:0001"}) {
            var answer=fixture.answer("review").put("jdEvidence",new JSONArray().put(invalid));
            assertTrue(InternshipPolicy.evaluate(c,j,answer.toString()).getBoolean("pendingModel"));
        }
    }
    @Test void formattingNormalizationReturnsActualOriginalSpan() {
        String original="【Ａ Ｉ应用】熟悉RAG\n知识库，编写ＰＲＤ。";
        assertEquals("【Ａ Ｉ应用】",InternshipPolicy.evidenceQuote(original,"【AI应用】"));
        assertEquals("RAG\n知识库",InternshipPolicy.evidenceQuote(original,"RAG知识库"));
        assertEquals("ＰＲＤ",InternshipPolicy.evidenceQuote(original,"PRD"));
        assertNull(InternshipPolicy.evidenceQuote(original,"熟悉Python"));
        assertNull(InternshipPolicy.evidenceQuote(original,"RAG知识库并负责模型训练"));
    }
    @Test void acceptsWhitespaceAndInvisibleFormattingWithoutRewritingStoredEvidence() {
        var c=fixture.profile().put("resumeText","参与库存分析工具需求分析并编写Ｐ Ｒ\nＤ。\u200b");
        var result=InternshipPolicy.evaluate(c,fixture.job(),fixture.answer("apply").toString());
        assertEquals("apply",result.getString("decision"));assertFalse(result.optBoolean("pendingModel"));
        assertEquals("编写Ｐ Ｒ\nＤ",result.getJSONArray("resumeEvidence").getString(0));
        assertTrue(c.getString("resumeText").contains(result.getJSONArray("resumeEvidence").getString(0)));
    }
    InternshipRunner runner(AiService ai) {
        var runner=new InternshipRunner(new InternshipStore(directory),ai,null,null,null,null);
        org.springframework.test.util.ReflectionTestUtils.setField(runner,"modelEnabled",true);return runner;
    }
    @Test void correctsOnlyOnceAndValidatesCorrectedReply() {
        var ai=mock(AiService.class);
        String bad=fixture.answer("review").put("resumeEvidence",new JSONArray().put("熟练Python模型训练")).toString();
        when(ai.sendRequest(anyString())).thenReturn(bad,fixture.answer("review").toString());
        var result=runner(ai).analyze(fixture.profile(),fixture.job());
        assertFalse(result.optBoolean("pendingModel"));assertEquals(2,result.getInt("validationAttempts"));
        verify(ai,times(2)).sendRequest(anyString());
    }
    @Test void repeatedInventedEvidenceRemainsBlockedAndAuditable() {
        var ai=mock(AiService.class);
        when(ai.sendRequest(anyString())).thenReturn(fixture.answer("apply").put("resumeEvidence",new JSONArray().put("熟练Python模型训练")).toString());
        var result=runner(ai).analyze(fixture.profile(),fixture.job());
        assertTrue(result.getBoolean("pendingModel"));assertEquals(2,result.getInt("validationAttempts"));
        assertEquals("熟练Python模型训练",result.getJSONObject("validationFailure").getString("quote"));
        verify(ai,times(2)).sendRequest(anyString());
    }
    @Test void validAnswerAndFormattingDifferenceNeverUseExtraModelCall() {
        var ai=mock(AiService.class);when(ai.sendRequest(anyString())).thenReturn(fixture.answer("apply").toString());
        var c=fixture.profile().put("resumeText","参与库存分析工具需求分析并编写P R D。");
        var result=runner(ai).analyze(c,fixture.job());assertEquals("apply",result.getString("decision"));
        assertEquals(1,result.getInt("validationAttempts"));verify(ai).sendRequest(anyString());
    }
    @Test void transportFailureDoesNotTriggerEvidenceRepairRetry() {
        var ai=mock(AiService.class);when(ai.sendRequest(anyString())).thenThrow(new IllegalStateException("断网"));
        assertTrue(runner(ai).analyze(fixture.profile(),fixture.job()).getBoolean("pendingModel"));verify(ai).sendRequest(anyString());
    }
}
