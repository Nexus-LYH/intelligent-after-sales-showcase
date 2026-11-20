package com.ias.finance;

import com.ias.trust.Problem;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import javax.sql.DataSource;
import java.util.*;
import static com.ias.finance.FinanceController.*;

@RestController
public final class MoneyReviewController {
 private final DataSource source;private final FinanceAccess access;
 public MoneyReviewController(DataSource source,FinanceAccess access){this.source=source;this.access=access;}
 @ModelAttribute public void noStore(jakarta.servlet.http.HttpServletResponse r){r.setHeader("Cache-Control","no-store");}
 @PostMapping("/api/finance/groups/{group}/money/reviews")
 public Object submit(@PathVariable String group,@RequestBody String raw,HttpServletRequest request)throws Exception{
  var b=body(raw,Set.of("operationId","reviewId","kind","accountVersion","groupVersion","subjectId","billId","lineId","proposedCents","coverageDigest","evidenceFileId","evidenceDigest","reason"));
  UUID op=key(b,request);var p=access.moneyPrincipal(request,true);var owner=access.moneyOwner(source,request,p,id(group));
  var proposal=new MoneyReviewStore.Proposal(id(b.get("reviewId")),text(b.get("kind")),number(b.get("accountVersion")),number(b.get("groupVersion")),id(b.get("subjectId")),id(b.get("billId")),b.get("lineId")==null?null:id(b.get("lineId")),number(b.get("proposedCents")),b.get("coverageDigest")==null?null:text(b.get("coverageDigest")),b.get("evidenceFileId")==null?null:id(b.get("evidenceFileId")),b.get("evidenceDigest")==null?null:text(b.get("evidenceDigest")),text(b.get("reason")));
  new MoneyReviewStore(source,access.review(request,p)).submit(p.actor(),owner,op,proposal);return Map.of("operationId",op.toString(),"reviewId",proposal.reviewId().toString(),"event","SUBMITTED");
 }
 @PostMapping("/api/finance/groups/{group}/money/reviews/{review}/{action:confirm|approve|verify}")
 public Object decide(@PathVariable String group,@PathVariable String review,@PathVariable String action,@RequestBody String raw,HttpServletRequest request)throws Exception{
  var b=body(raw,"verify".equals(action)?Set.of("operationId","verifiedCents","evidenceFileId","evidenceDigest","reviewerNote"):Set.of("operationId"));UUID op=key(b,request);var p="confirm".equals(action)?access.reviewPrincipal(request):access.moneyPrincipal(request,true);UUID groupId=id(group);var owner=JdbcFeeGroups.groupOwner(source,p.actor(),groupId);
  String event=switch(action){case "confirm"->"CUSTOMER_CONFIRMED";case "approve"->"FINANCE_APPROVED";case "verify"->"CORRECTION_VERIFIED";default->throw new Problem(400,"INVALID_REVIEW_EVENT");};
  var store=new MoneyReviewStore(source,access.review(request,p));
  if("verify".equals(action))store.verifyCorrection(p.actor(),owner,op,id(review),new MoneyReviewStore.Verification(number(b.get("verifiedCents")),id(b.get("evidenceFileId")),text(b.get("evidenceDigest")),text(b.get("reviewerNote"))));
  else store.decide(p.actor(),owner,op,id(review),event);
  return Map.of("operationId",op.toString(),"reviewId",review,"event",event);
 }
 @GetMapping("/api/finance/groups/{group}/money/reviews/{review}")
 public Object read(@PathVariable String group,@PathVariable String review,HttpServletRequest request)throws Exception{
  var p=access.reviewPrincipal(request);var owner=JdbcFeeGroups.groupOwner(source,p.actor(),id(group));var auth=access.review(request,p);
  MoneyReviewStore.Review item;try(var db=source.getConnection()){item=MoneyReviewStore.read(db,owner,id(review),false);}
  auth.authorize(p.actor(),owner,"READ",item.proposal());var x=item.proposal();var out=new LinkedHashMap<String,Object>();
  out.put("reviewId",x.reviewId().toString());out.put("kind",x.kind());out.put("billId",x.billId().toString());out.put("lineId",x.lineId()==null?null:x.lineId().toString());out.put("proposedCents",Long.toString(x.proposedCents()));out.put("accountVersion",Long.toString(x.accountVersion()));out.put("groupVersion",Long.toString(x.groupVersion()));out.put("coverageDigest",x.coverageDigest());out.put("reason",x.reason());out.put("events",item.events());
  if("EMPLOYEE".equals(p.type()))out.put("applicantUserId",Long.toString(item.applicantId()));
  if("EMPLOYEE".equals(p.type())&&"CORRECTION".equals(x.kind())){
   out.put("originalPaymentId",x.subjectId().toString());out.put("evidenceFileId",x.evidenceFileId().toString());out.put("evidenceDigest",x.evidenceDigest());out.put("context",access.correctionContext(request,p,owner,item));
  }
  return out;
 }
 @GetMapping("/api/finance/groups/{group}/money/reviews/{review}/operations/{operation}")
 public Object operation(@PathVariable String group,@PathVariable String review,@PathVariable String operation,HttpServletRequest request)throws Exception{
  var p=access.reviewPrincipal(request);var owner=JdbcFeeGroups.groupOwner(source,p.actor(),id(group));UUID reviewId=id(review),op=id(operation);
  try(var db=source.getConnection()){var item=MoneyReviewStore.read(db,owner,reviewId,false);access.review(request,p).authorize(p.actor(),owner,"READ",item.proposal());try(var q=JdbcMoneyLedger.query(db,"SELECT event_kind FROM finance_money_review_event WHERE tenant_id=? AND review_id=? AND operation_id=? AND actor_id=?",owner.tenantId(),reviewId.toString(),op.toString(),p.actor().userId());var r=q.executeQuery()){if(!r.next())throw new Problem(404,"OPERATION_NOT_OBSERVED");return Map.of("reviewId",reviewId.toString(),"operationId",op.toString(),"event",r.getString(1));}}
 }
}
