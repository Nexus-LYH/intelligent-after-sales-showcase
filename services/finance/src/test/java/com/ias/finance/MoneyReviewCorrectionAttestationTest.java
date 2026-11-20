package com.ias.finance;

import com.ias.trust.Problem;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MoneyReviewCorrectionAttestationTest {
 TrustedManualMoneyTest fixture;MoneyReviewStore store;MoneyReviewStore.Proposal proposal;
 final JdbcFeeGroups.Actor reviewer=new JdbcFeeGroups.Actor(1,11);
 @BeforeEach void setup()throws Exception{
  fixture=new TrustedManualMoneyTest();fixture.setup();var payment=fixture.command();fixture.production().apply(fixture.f.f.actor,fixture.f.f.owner,UUID.randomUUID(),payment);
  proposal=new MoneyReviewStore.Proposal(UUID.randomUUID(),"CORRECTION",2,1,payment.payment().id(),fixture.bill,null,90000,null,UUID.randomUUID(),"c".repeat(64),"Correct documented bank amount");
  store=new MoneyReviewStore(fixture.f.f.source,(a,o,e,p)->{});store.submit(fixture.f.f.actor,fixture.f.f.owner,UUID.randomUUID(),proposal);
 }
 MoneyReviewStore.Verification verification(long cents,String note){return new MoneyReviewStore.Verification(cents,proposal.evidenceFileId(),proposal.evidenceDigest(),note);}
 @Test void legacyAcknowledgementAndDifferentVerifiedAmountCannotApprove()throws Exception{
  assertEquals("CORRECTION_ATTESTATION_REQUIRED",assertThrows(Problem.class,()->store.decide(reviewer,fixture.f.f.owner,UUID.randomUUID(),proposal.reviewId(),"CORRECTION_VERIFIED")).code);
  assertThrows(IllegalStateException.class,()->store.verifyCorrection(reviewer,fixture.f.f.owner,UUID.randomUUID(),proposal.reviewId(),verification(80000,"Verified different amount")));
  assertEquals(0,fixture.f.f.count("finance_correction_attestation"));assertEquals(1,fixture.f.f.count("finance_money_review_event"));
 }
 @Test void independentAmountAndEvidenceAreImmutableAcrossReplay()throws Exception{
  var op=UUID.randomUUID();var verified=verification(90000,"Checked original bank record");
  assertEquals("INDEPENDENT_REVIEW_REQUIRED",assertThrows(Problem.class,()->store.verifyCorrection(fixture.f.f.actor,fixture.f.f.owner,op,proposal.reviewId(),verified)).code);
  store.verifyCorrection(reviewer,fixture.f.f.owner,op,proposal.reviewId(),verified);store.verifyCorrection(reviewer,fixture.f.f.owner,op,proposal.reviewId(),verified);
  assertEquals("REVIEW_ALREADY_DECIDED",assertThrows(IllegalStateException.class,()->store.verifyCorrection(reviewer,fixture.f.f.owner,UUID.randomUUID(),proposal.reviewId(),verified)).getMessage());
  assertThrows(IllegalStateException.class,()->store.verifyCorrection(reviewer,fixture.f.f.owner,op,proposal.reviewId(),verification(90000,"Changed review explanation")));
  assertEquals(1,fixture.f.f.count("finance_correction_attestation"));assertEquals(2,fixture.f.f.count("finance_money_review_event"));
 }
 @Test void attestationInsertFailureRollsBackEventAndCanRecoverOriginalKey()throws Exception{
  try(var db=fixture.f.f.source.getConnection();var query=db.createStatement()){query.execute("ALTER TABLE finance_correction_attestation ADD CONSTRAINT fail_attestation CHECK(reviewer_id<>11)");}
  var op=UUID.randomUUID();var verified=verification(90000,"Checked original bank record");
  assertThrows(java.sql.SQLException.class,()->store.verifyCorrection(reviewer,fixture.f.f.owner,op,proposal.reviewId(),verified));
  assertEquals(0,fixture.f.f.count("finance_correction_attestation"));assertEquals(1,fixture.f.f.count("finance_money_review_event"));
  try(var db=fixture.f.f.source.getConnection();var query=db.createStatement()){query.execute("ALTER TABLE finance_correction_attestation DROP CONSTRAINT fail_attestation");}
  store.verifyCorrection(reviewer,fixture.f.f.owner,op,proposal.reviewId(),verified);assertEquals(1,fixture.f.f.count("finance_correction_attestation"));
 }
}
