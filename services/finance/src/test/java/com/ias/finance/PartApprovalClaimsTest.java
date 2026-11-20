package com.ias.finance;
import org.junit.jupiter.api.*;
import org.h2.jdbcx.JdbcDataSource;
import org.h2.tools.RunScript;
import java.util.*;
import java.sql.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.ias.finance.FeeGroup.*;
import static com.ias.finance.JdbcFeeGroups.*;

class PartApprovalClaimsTest {
 JdbcDataSource source;Identity owner=new Identity(1,2,UUID.randomUUID());UUID approval=UUID.randomUUID(),line=UUID.randomUUID(),ticket=UUID.randomUUID(),round=UUID.randomUUID();
 @BeforeEach void setup()throws Exception{source=new JdbcDataSource();source.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");try(var db=source.getConnection()){RunScript.execute(db,new InputStreamReader(getClass().getResourceAsStream("/db/migration/V13__part_approval_claim.sql"),StandardCharsets.UTF_8));}}
 Submit command(){return new Submit(UUID.randomUUID(),UUID.randomUUID(),0,Set.of(),List.of(new PlanLine(line,ticket,round,100,false)),new PublishedRule("v1",1000));}
 QuoteDetails details(long quantity){return new QuoteDetails(true,"claim test",Map.of(line,"PART"),Map.of(line,new PartBinding(approval,1,3,4,quantity,"pcs","FULL_LINE_SINGLE_USE_V1")));}
 @Test void sameGroupRevisionRetainsClaimButOtherGroupOrChangedMaterialCannotReuseIt()throws Exception{try(var db=source.getConnection()){
  PartApprovalClaims.reserve(db,owner,command(),details(2));PartApprovalClaims.reserve(db,owner,command(),details(2));
  var proof=PartApprovalClaims.proof(db,owner,command(),details(2));assertEquals(1,proof.size());assertEquals(approval.toString(),proof.getFirst().get("approvalId"));
  assertThrows(IllegalStateException.class,()->PartApprovalClaims.proof(db,new Identity(1,2,UUID.randomUUID()),command(),details(2)));
  assertThrows(IllegalStateException.class,()->PartApprovalClaims.proof(db,owner,command(),details(3)));
  assertEquals("PART_APPROVAL_ALREADY_BOUND",assertThrows(IllegalStateException.class,()->PartApprovalClaims.reserve(db,new Identity(1,2,UUID.randomUUID()),command(),details(2))).getMessage());
  assertThrows(IllegalStateException.class,()->PartApprovalClaims.reserve(db,owner,command(),details(3)));
 }}
 @Test void failedQuoteTransactionDoesNotLeaveClaim()throws Exception{try(var db=source.getConnection()){db.setAutoCommit(false);PartApprovalClaims.reserve(db,owner,command(),details(2));db.rollback();db.setAutoCommit(true);PartApprovalClaims.reserve(db,new Identity(1,2,UUID.randomUUID()),command(),details(3));}}
 @Test void concurrentFaultGroupsCanClaimMaterialOnlyOnce()throws Exception{try(var pool=Executors.newFixedThreadPool(2)){
  var start=new CountDownLatch(1);var futures=new ArrayList<Future<Boolean>>();for(int i=0;i<2;i++)futures.add(pool.submit(()->{start.await();try(var db=source.getConnection()){db.setAutoCommit(false);try{PartApprovalClaims.reserve(db,new Identity(1,2,UUID.randomUUID()),command(),details(2));db.commit();return true;}catch(IllegalStateException conflict){db.rollback();return false;}}}));start.countDown();int winners=0;for(var f:futures)if(f.get(10,TimeUnit.SECONDS))winners++;assertEquals(1,winners);
 }}
}
