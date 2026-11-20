package com.ias.finance;

import com.ias.trust.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.ias.finance.FinanceController.*;

/** Minimal, current-authorized original quote facts for the approval owner. Never ticket text. */
@RestController
public final class FeeApprovalContextController {
 @FunctionalInterface interface Calls { Map<String,Object> call(String edge,String path,Map<String,Object> body,HttpServletRequest r); }
 @FunctionalInterface interface Trust { void check(HttpServletRequest r); }
 private final DataSource source; private final Calls calls; private final Trust trust;
 @Autowired public FeeApprovalContextController(DataSource source){this(source,(e,p,b,r)->Chain.call(e,p,b,Chain.trace(r),Chain.deadline(r)),r->{if(!Chain.active()||!r.isSecure()||!"approval-finance".equals(r.getAttribute("ias.chain.edge")))throw new Problem(403,"SERVICE_AUTHENTICATION_FAILED");});}
 FeeApprovalContextController(DataSource source,Calls calls,Trust trust){this.source=source;this.calls=calls;this.trust=trust;}
 @ModelAttribute public void noStore(jakarta.servlet.http.HttpServletResponse r){r.setHeader("Cache-Control","no-store");}
 private Map<String,Object> call(String edge,String path,Map<String,Object> body,HttpServletRequest r){var f=calls.call(edge,path,body,r);Chain.fresh(f,Chain.deadline(r));return f;}
 @PostMapping("/internal/finance/fee-approval-context") public Object context(@RequestBody String raw,HttpServletRequest r)throws Exception{
  trust.check(r);var b=body(raw,Set.of("sessionToken","groupId","quoteId","ticketId"));String token=text(b.get("sessionToken"));UUID group=id(b.get("groupId")),quoteId=id(b.get("quoteId")),ticket=id(b.get("ticketId"));
  var identity=call("finance-identity","/internal/identity/domain-authorizations",Map.of("sessionToken",token,"purpose","FEE_READ"),r);
  if(!Boolean.TRUE.equals(identity.get("enabled"))||!"EMPLOYEE".equals(identity.get("identityType")))throw new Problem(403,"ACCESS_DENIED");
  String tenant=positive(identity.get("tenantId")),actor=positive(identity.get("userId"));
  if(!Boolean.TRUE.equals(identity.get("departmentEnabled"))||!(identity.get("permissions") instanceof List<?> permissions)||!permissions.contains("approval.fees.read"))throw new Problem(403,"ACCESS_DENIED");
  var scope=call("finance-ticket","/internal/ticket/finance-context",Map.of("sessionToken",token,"ticketId",ticket.toString(),"purpose","FEE_READ"),r);
  String company=positive(scope.get("companyId")),relation=id(scope.get("faultRelationId")).toString();
  if(!tenant.equals(scope.get("tenantId"))||!ticket.toString().equals(scope.get("ticketId"))||!(identity.get("companyIds") instanceof List<?> companies)||!companies.contains(company))throw new Problem(404,"TARGET_NOT_ACCESSIBLE");
  if(!group.equals(UUID.nameUUIDFromBytes(("finance:"+tenant+":"+company+":"+relation).getBytes(StandardCharsets.UTF_8))))throw new Problem(404,"TARGET_NOT_ACCESSIBLE");
  var out=new LinkedHashMap<String,Object>();
  try(var db=source.getConnection()){
   // One read-only repeatable snapshot: no mixed group/quote/confirmation facts and no GET writes.
   db.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);db.setReadOnly(true);db.setAutoCommit(false);
   try{
    long groupVersion,currentBasis;try(var q=prepare(db,"SELECT row_version,customer_company_id,b_cents FROM finance_fee_group WHERE tenant_id=? AND fault_group_id=?",tenant,group.toString());var rows=q.executeQuery()){if(!rows.next()||!company.equals(rows.getString(2)))throw new Problem(404,"TARGET_NOT_ACCESSIBLE");groupVersion=rows.getLong(1);currentBasis=rows.getLong(3);}
    JdbcFeeGroups.QuoteVersion quote;try(var q=prepare(db,"SELECT quote_json FROM finance_quote_version WHERE tenant_id=? AND fault_group_id=? AND application_id=?",tenant,group.toString(),quoteId.toString());var rows=q.executeQuery()){if(!rows.next())throw new Problem(404,"TARGET_NOT_ACCESSIBLE");quote=JSON.readValue(rows.getString(1),JdbcFeeGroups.QuoteVersion.class);}
    var owner=new FeeGroup.Identity(Long.parseLong(tenant),Long.parseLong(company),group);if(!owner.equals(quote.basis().owner())||!quoteId.equals(quote.command().applicationId()))throw new Problem(503,"FINANCE_QUOTE_FACT_MISMATCH");
    var original=quote.command().additions();if(original.isEmpty()||original.stream().anyMatch(l->!ticket.equals(l.ticketId())||!original.getFirst().roundId().equals(l.roundId())))throw new Problem(404,"TARGET_NOT_ACCESSIBLE");
    String digest=JdbcFeeGroups.quoteDigest(owner,quote.command(),quote.details());
    try(var q=prepare(db,"SELECT actor_id,operation_id,command_digest,result_digest,source_state,expected_group_version FROM finance_quote_source WHERE tenant_id=? AND fault_group_id=? AND application_id=?",tenant,group.toString(),quoteId.toString());var rows=q.executeQuery()){
     if(!rows.next()||!"COMMITTED".equals(rows.getString(5))||!digest.equals(rows.getString(3))||!quote.command().operationId().toString().equals(rows.getString(2))||rows.getLong(6)!=quote.command().expectedVersion()||rows.getString(4)==null||!rows.getString(4).matches("[0-9a-f]{64}"))throw new Problem(503,"FINANCE_SOURCE_NOT_COMMITTED");
     out.put("sourceActorUserId",rows.getString(1));out.put("sourceOperationId",rows.getString(2));out.put("sourceCommandDigest",rows.getString(3));out.put("sourceResultDigest",rows.getString(4));
    }
    boolean current;try(var q=prepare(db,"SELECT application_id FROM finance_quote_version WHERE tenant_id=? AND fault_group_id=? ORDER BY group_version DESC LIMIT 1",tenant,group.toString());var rows=q.executeQuery()){current=rows.next()&&quoteId.toString().equals(rows.getString(1));}
    try(var q=prepare(db,"SELECT application_id FROM finance_quote_decision WHERE tenant_id=? AND application_id=?",tenant,quoteId.toString());var rows=q.executeQuery()){if(rows.next())current=false;}
    current=current&&original.getFirst().roundId().toString().equals(scope.get("roundId"))&&currentBasis<=quote.basis().bCents();
    out.put("tenantId",tenant);out.put("actorUserId",actor);out.put("companyId",company);out.put("ticketId",ticket.toString());out.put("roundId",original.getFirst().roundId().toString());out.put("faultRelationId",relation);out.put("groupId",group.toString());out.put("quoteId",quoteId.toString());out.putAll(basis(quote.basis()));out.put("basisGroupVersion",Long.toString(quote.basis().groupVersion()));out.remove("firstFinanceTrigger");out.put("everRequiredFinance",quote.basis().firstFinanceTrigger()!=null);out.put("currentGroupVersion",Long.toString(groupVersion));out.put("chargesCustomer",quote.details().chargesCustomer());out.put("quoteDigest",digest);out.put("current",current);
    if(!quote.details().partBindings().isEmpty()){out.put("coverageVersion","2");out.put("partApprovalClaims",PartApprovalClaims.proof(db,owner,quote.command(),quote.details()));}out.put("lines",original.stream().map(l->JdbcFeeGroups.coverageLine(l,quote.details())).toList());
    out.put("customerConfirmation",null);try(var q=prepare(db,"SELECT operation_id,actor_id,quote_group_version,confirmed_at FROM finance_customer_confirmation WHERE tenant_id=? AND application_id=? AND fault_group_id=?",tenant,quoteId.toString(),group.toString());var rows=q.executeQuery()){
     if(rows.next()){if(rows.getLong(3)!=quote.basis().groupVersion())throw new Problem(503,"FINANCE_CONFIRMATION_FACT_MISMATCH");out.put("customerConfirmation",Map.of("operationId",rows.getString(1),"actorUserId",rows.getString(2),"quoteId",quoteId.toString(),"quoteVersion",rows.getString(3),"quoteDigest",digest,"confirmedAt",rows.getTimestamp(4).toInstant().toString()));}
    }
    db.rollback();
   }catch(Exception e){try{db.rollback();}catch(SQLException rollback){e.addSuppressed(rollback);}throw e;}
  }
  // Current employee authority is checked again after reading the immutable original facts.
  var fresh=call("finance-identity","/internal/identity/domain-authorizations",Map.of("sessionToken",token,"purpose","FEE_READ"),r);
  if(!tenant.equals(fresh.get("tenantId"))||!actor.equals(fresh.get("userId"))||!Boolean.TRUE.equals(fresh.get("enabled"))||!"EMPLOYEE".equals(fresh.get("identityType"))||!Boolean.TRUE.equals(fresh.get("departmentEnabled"))||!(fresh.get("permissions") instanceof List<?> p)||!p.contains("approval.fees.read")||!(fresh.get("companyIds") instanceof List<?> c)||!c.contains(company))throw new Problem(403,"ACCESS_DENIED");
  out.put("checkedAt",Instant.now().toString());return out;
 }
 private static PreparedStatement prepare(Connection db,String sql,Object... values)throws SQLException{var q=db.prepareStatement(sql);try{for(int i=0;i<values.length;i++)q.setObject(i+1,values[i]);return q;}catch(SQLException e){q.close();throw e;}}
 private static String positive(Object value){String s=text(value);if(!s.matches("[1-9][0-9]{0,18}"))throw new Problem(503,"INVALID_FINANCE_OWNER_FACT");try{Long.parseLong(s);}catch(Exception e){throw new Problem(503,"INVALID_FINANCE_OWNER_FACT");}return s;}
}
