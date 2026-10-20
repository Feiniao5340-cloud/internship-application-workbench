package com.getjobs.worker.parallel;
import com.getjobs.worker.boss.InternshipPolicy;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class PortableDefaultsTest {
    @Test void sharedDistributionContainsNoPersonalProfileOrImplicitReviewPermission(){JSONObject defaults=InternshipPolicy.defaults();assertFalse(defaults.getBoolean("confirmed"));assertFalse(defaults.getBoolean("autoConfirmReview"));assertTrue(defaults.getString("resumeText").isEmpty());assertTrue(defaults.getString("targetDirections").isEmpty());assertTrue(defaults.getString("greeting").isEmpty());assertEquals("scan",defaults.getString("mode"));assertTrue(defaults.getJSONArray("cities").isEmpty());}
    @Test void changingReviewPermissionInvalidatesPreviousJudgment(){JSONObject a=InternshipPolicy.defaults(),b=new JSONObject(a.toString()).put("autoConfirmReview",true);assertNotEquals(InternshipPolicy.fingerprint(a),InternshipPolicy.fingerprint(b));}
    @Test void availabilityAndResumeChoiceMustBeProvidedByEachUser(){JSONObject d=InternshipPolicy.defaults();assertEquals(0,d.getInt("shixisengDays"));assertEquals("",d.getString("shixisengPeriod"));assertEquals("",d.getString("shixisengResumeType"));assertEquals(0,d.getInt("minDailySalary"));assertTrue(d.getString("constraintsText").isBlank());assertTrue(d.getString("excludedDirections").isBlank());assertTrue(d.getJSONArray("keywords").isEmpty());assertTrue(d.getJSONArray("companyBlacklist").isEmpty());InternshipPolicy.validateSettings(d);}
}
