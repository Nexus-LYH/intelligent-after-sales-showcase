package com.ias.finance;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.*;
import static com.ias.finance.FinanceController.*;
import static com.ias.finance.JdbcMoneyLedger.*;
import static com.ias.finance.MoneyLedger.*;

@RestController
public final class MoneyController {
 final DataSource source;final FinanceAccess access;
 public MoneyController(DataSource source,FinanceAccess access){this.source=source;this.access=access;}
 @ModelAttribute public void noStore(jakarta.servlet.http.HttpServletResponse r){r.setHeader("Cache-Control","no-store");}
 @GetMapping("/api/finance/groups/{group}/money") public Object balance(@PathVariable String group,HttpServletRequest r)throws Exception{
  var p=access.moneyPrincipal(r,false);var o=access.moneyOwner(source,r,p,id(group));return view(new JdbcMoneyLedger(source,access.ledger(r,p)).read(p.actor(),o));
 }
 @GetMapping("/api/finance/groups/{group}/money/operations/{operation}") public Object operation(@PathVariable String group,@PathVariable String operation,HttpServletRequest r)throws Exception{
  var p=access.moneyPrincipal(r,false);var o=access.moneyOwner(source,r,p,id(group));return safe(new JdbcMoneyLedger(source,access.ledger(r,p)).operation(p.actor(),o,id(operation)));
 }
 @GetMapping("/api/finance/groups/{group}/money/entries") public Object history(@PathVariable String group,@RequestParam(defaultValue="0")String afterVersion,@RequestParam(defaultValue="20")String limit,HttpServletRequest r)throws Exception{
  var p=access.moneyPrincipal(r,false);var o=access.moneyOwner(source,r,p,id(group));long size=number(limit);if(size<1||size>50)throw new IllegalArgumentException("INVALID_PAGE");return Map.of("entries",new JdbcMoneyLedger(source,access.ledger(r,p)).history(p.actor(),o,number(afterVersion),(int)size));
 }
 @PostMapping("/api/finance/groups/{group}/money/{action:assess|lower|collect|reverse|refund|rebook}") public Object write(@PathVariable String group,@PathVariable String action,@RequestBody String raw,HttpServletRequest r)throws Exception{
  var fields=new HashSet<>(Set.of("operationId","expectedVersion","expectedGroupVersion"));
  fields.addAll(switch(action){case "assess"->Set.of("billId","completionId","coverageDigest","lines");case "lower"->Set.of("billId","lineId","newCents","adjustmentApprovalId","coverageDigest","reason");case "collect"->Set.of("billId","paymentId","amountCents","voucher","correctionOf");case "reverse"->Set.of("originalPaymentId","correctionEvidenceId","reason");case "rebook"->Set.of("originalPaymentId","correctionEvidenceId","reason","billId","paymentId","amountCents","voucher");case "refund"->Set.of("refundId","originalPaymentId","amountCents","voucher");default->throw new IllegalArgumentException();});
  var b=body(raw,fields);UUID op=key(b,r);long v=number(b.get("expectedVersion")),g=number(b.get("expectedGroupVersion"));
  Command command=switch(action){
   case "assess"->{if(!(b.get("lines") instanceof List<?> lines)||lines.isEmpty()||lines.size()>50)throw new IllegalArgumentException("INVALID_COVERAGE");var covered=new HashMap<UUID,Long>();for(Object item:lines){if(!(item instanceof Map<?,?> line)||!line.keySet().equals(Set.of("lineId","amountCents")))throw new IllegalArgumentException("INVALID_COVERAGE");if(covered.putIfAbsent(id(line.get("lineId")),number(line.get("amountCents")))!=null)throw new IllegalArgumentException("DUPLICATE_LINE");}yield new Assess(v,g,id(b.get("billId")),new Approval(id(b.get("completionId")),text(b.get("coverageDigest")),covered));}
   case "lower"->new Lower(v,g,id(b.get("billId")),id(b.get("lineId")),number(b.get("newCents")),id(b.get("adjustmentApprovalId")),text(b.get("coverageDigest")),text(b.get("reason")));
   case "collect"->new Collect(v,g,id(b.get("billId")),new Payment(id(b.get("paymentId")),id(b.get("billId")),number(b.get("amountCents")),voucher(b.get("voucher")),b.get("correctionOf")==null?null:id(b.get("correctionOf"))));
   case "reverse"->new Reverse(v,g,id(b.get("originalPaymentId")),id(b.get("correctionEvidenceId")),text(b.get("reason")));
   case "rebook"->new Rebook(v,g,id(b.get("originalPaymentId")),id(b.get("correctionEvidenceId")),text(b.get("reason")),new Payment(id(b.get("paymentId")),id(b.get("billId")),number(b.get("amountCents")),voucher(b.get("voucher")),id(b.get("originalPaymentId"))));
   case "refund"->new RefundPaid(v,g,new Refund(id(b.get("refundId")),id(b.get("originalPaymentId")),number(b.get("amountCents")),voucher(b.get("voucher"))));
   default->throw new IllegalArgumentException("INVALID_LEDGER_COMMAND");
  };
  var p=access.moneyPrincipal(r,true);var o=access.moneyOwner(source,r,p,id(group));return safe(new JdbcMoneyLedger(source,access.ledger(r,p)).apply(p.actor(),o,op,command));
 }
 static Voucher voucher(Object input){if(!(input instanceof Map<?,?> v)||!v.keySet().equals(Set.of("reference","date","method","privateFileId","evidenceDigest")))throw new IllegalArgumentException("INVALID_VOUCHER");String date=text(v.get("date"));LocalDate day=LocalDate.parse(date);if(!day.toString().equals(date))throw new IllegalArgumentException("INVALID_DATE");return new Voucher(text(v.get("reference")),day,text(v.get("method")),id(v.get("privateFileId")),text(v.get("evidenceDigest")));}
 static Map<String,Object> view(State s){return Map.of("version",Long.toString(s.version()),"currency","CNY","receivableCents",Long.toString(s.receivable()),"collectedCents",Long.toString(s.collected()),"refundedCents",Long.toString(s.refunded()),"settledCents",Long.toString(s.settled()),"outstandingCents",Long.toString(s.outstanding()),"overpaymentCents",Long.toString(s.overpayment()),"notice","人工登记，未接支付渠道");}
 static Object safe(Receipt r){return Map.of("operationId",r.operationId().toString(),"groupId",r.owner().faultGroupId().toString(),"groupVersion",Long.toString(r.groupVersion()),"action",r.action(),"balance",view(r.balance()));}
}
