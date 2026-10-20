package com.getjobs.worker.boss;

import org.json.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class InternshipPrefilterTest {
    @TempDir Path directory;
    JSONObject intern(){return new JSONObject().put("title","库存分析实习生").put("salary","120-240元/天")
            .put("experience","在校/应届").put("jd","负责产品需求分析，欢迎实习生。");}
    @Test void monthlyPayIsRejectedEvenWhenTitleClaimsInternshipAndDigitsAreObfuscated(){
        assertFalse(InternshipPolicy.formalReason(intern().put("salary","8-12K·13薪")).isBlank());
        assertFalse(InternshipPolicy.formalReason(intern().put("salary","\ue034\ue031-\ue036\ue031K")).isBlank());
        assertFalse(InternshipPolicy.formalReason(intern().put("salary","4000元/月")).isBlank());
        assertTrue(InternshipPolicy.formalReason(intern().put("salary","日薪120元/天，月结")).isBlank());
    }
    @Test void professionalYearsAreRejectedButInternshipExperienceAndPreferencesAreNot(){
        assertFalse(InternshipPolicy.formalReason(intern().put("experience","3-5年")).isBlank());
        assertFalse(InternshipPolicy.formalReason(intern().put("jd","需具备3年以上互联网产品工作经验。")).isBlank());
        assertFalse(InternshipPolicy.formalReason(intern().put("jd","要求一年以上正式工作经验。")).isBlank());
        assertFalse(InternshipPolicy.formalReason(intern().put("jd","具有2年以上设计经验，能独立完成设计任务。")).isBlank());
        assertTrue(InternshipPolicy.formalReason(intern().put("jd","有2年库存分析实习经验者优先。")).isBlank());
        assertTrue(InternshipPolicy.formalReason(intern().put("jd","有2年AI工具使用经验，熟悉常见大模型工具。")).isBlank());
        assertTrue(InternshipPolicy.formalReason(intern().put("jd","有1年以上库存分析实习工作经验者优先。")).isBlank());
        assertTrue(InternshipPolicy.formalReason(intern().put("jd","有1年以上产品工作经验优先，不作强制要求。")).isBlank());
        assertTrue(InternshipPolicy.formalReason(intern().put("jd","可每周实习5天，持续3个月。")).isBlank());
    }
    @Test void cardTextFallbackFindsSalaryAndYearsWithoutLookingAtUnrelatedCompanyNames(){
        assertFalse(InternshipPolicy.formalReason(new JSONObject().put("cardText","数据分析师\n\ue034\ue031K\n经验不限\n本科")).isBlank());
        assertFalse(InternshipPolicy.formalReason(new JSONObject().put("cardText","数据分析师\n面议\n3-5年\n本科")).isBlank());
        assertTrue(InternshipPolicy.formalReason(new JSONObject().put("cardText","库存分析实习生\n面议\n在校/应届\nSKG科技")).isBlank());
    }
    @Test void excludedRecordsStayInHistoryButNotInCandidatePoolOrRoundTotals(){
        var store=new InternshipStore(directory);String round=store.beginRound(3);
        for(String decision:new String[]{"apply","skip","review"})store.save(decision,new JSONObject().put("job",intern())
                .put("status","screened").put("roundId",round).put("result",new JSONObject().put("decision",decision)));
        store.save("formal",new JSONObject().put("job",intern().put("salary","20-30K"))
                .put("status","screened").put("roundId",round).put("result",new JSONObject().put("decision","skip")));
        assertEquals(1,store.archiveFormalRecords());assertEquals(4,store.records().length());assertEquals(3,store.candidateRecords().length());
        store.exclude("card1",intern().put("salary","20K"),"月薪",round);
        store.exclude("card1",intern().put("salary","20K"),"月薪",round);
        var summary=store.roundSummary();assertEquals(3,summary.getInt("total"));assertEquals(1,summary.getInt("apply"));
        assertEquals(1,summary.getInt("skip"));assertEquals(1,summary.getInt("review"));assertEquals(1,summary.getInt("preExcluded"));
        store.beginRound(3);assertEquals(0,store.roundSummary().getInt("total"));
    }
}
