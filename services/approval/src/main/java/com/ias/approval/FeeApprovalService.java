package com.ias.approval;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Clock;
import java.util.*;
import static com.ias.approval.FeeApproval.*;

@Service
public final class FeeApprovalService {
    public record SubmitBody(String operationId,String applicationId,String ticketId,String groupId,String quoteId){}
    public record RefreshBody(String operationId,String expectedVersion){}
    public record DecisionBody(String operationId,String expectedVersion,String decision,String reason){}
    public record WithdrawBody(String operationId,String expectedVersion,String reason){}
    private final DataSource source;private final FeeApprovalAuthority access;
    @Autowired public FeeApprovalService(DataSource source){this(source,new FeeApprovalAuthority(source));}
    FeeApprovalService(DataSource source,FeeApprovalAuthority access){this.source=source;this.access=access;}
    private FeeApprovalStore local(){return new FeeApprovalStore(source);}
    public Map<String,Object> quoteContext(HttpServletRequest r,UUID ticket,UUID group,UUID quote){
        var actor=access.principal(r);var prepared=access.prepare(r,actor,ticket,group,quote,null,null);var facts=prepared.facts;var b=facts.basis();
        if(!prepared.engineer&&!facts.actorSupervisor()&&!facts.actorFinance())throw fail(403,"CURRENT_FEE_QUALIFICATION_REQUIRED");
        var result=new LinkedHashMap<String,Object>();result.put("ticketId",ticket.toString());result.put("roundId",b.roundId().toString());result.put("companyId",Long.toString(b.companyId()));result.put("groupId",group.toString());result.put("quoteId",quote.toString());result.put("quoteVersion",Long.toString(b.quoteVersion()));result.put("generation",Long.toString(b.generation()));result.put("chargesCustomer",b.chargesCustomer());result.put("financialReviewRequired",b.financialReviewRequired());result.put("basis",basis(b));result.put("lines",prepared.lines);result.put("coverageDigest",prepared.coverage);result.put("customerConfirmation",confirmation(facts.confirmation()));result.put("current",facts.currentQuote());result.put("ownerAcksComplete",facts.ownerAcksComplete());
        try(var c=source.getConnection()){String existing=FeeApprovalPersistence.scalar(c,"SELECT id FROM fee_approval_application WHERE tenant_id=? AND group_id=? AND quote_id=?",actor.tenantId(),group,quote);result.put("existingApplicationId",existing);result.put("canSubmit",existing==null&&prepared.engineer&&facts.currentQuote()&&facts.ownerAcksComplete());}catch(SQLException e){throw fail(503,"DEPENDENCY_UNAVAILABLE");}
        result.put("checkedAt",java.time.Instant.now().toString());return result;
    }
    private static Map<String,Object> basis(Basis b){return Map.of("qCents",Long.toString(b.q()),"cCents",Long.toString(b.c()),"hCents",Long.toString(b.h()),"bCents",Long.toString(b.b()),"thresholdCents",Long.toString(b.threshold()),"ruleVersion",b.ruleVersion(),"everRequiredFinance",b.everFinance());}
    private static Map<String,Object> confirmation(Confirmation c){return c==null?null:Map.of("operationId",c.id().toString(),"quoteId",c.quoteId().toString(),"quoteVersion",Long.toString(c.quoteVersion()),"quoteDigest",c.quoteDigest());}
    public Map<String,Object> submit(HttpServletRequest r,SubmitBody body){
        Actor actor=access.principal(r);UUID id=id(body.applicationId()),operation=id(body.operationId()),ticket=id(body.ticketId()),group=id(body.groupId()),quote=id(body.quoteId());
        Application old=local().local(actor.tenantId(),id);if(old!=null&&(!old.basis().ticketId().equals(ticket)||!old.basis().groupId().equals(group)||!old.basis().quoteId().equals(quote)))throw fail(409,"APPLICATION_BASIS_CONFLICT");
        var known=local().localOperation(actor,operation);if(known!=null&&(!known.id().equals(id)||!known.basis().ticketId().equals(ticket)||!known.basis().groupId().equals(group)||!known.basis().quoteId().equals(quote)))throw fail(409,"OPERATION_BINDING_CONFLICT");var prepared=known==null?access.prepare(r,actor,ticket,group,quote,Action.SUBMIT,old):access.recovery(r,actor,old);var basis=old==null?prepared.facts.basis():old.basis();
        var result=new FeeApprovalStore(source,prepared,Clock.systemUTC()).execute(actor,new Command(operation,id,Action.SUBMIT,0,basis,null));return view(result,List.of(),operation,true);
    }
    public Map<String,Object> refresh(HttpServletRequest r,UUID id,RefreshBody body){return write(r,id,id(body.operationId()),Action.REFRESH,version(body.expectedVersion()),null);}
    public Map<String,Object> decide(HttpServletRequest r,UUID id,DecisionBody body){Action action;try{action=Action.valueOf(body.decision());}catch(Exception e){throw fail(400,"INVALID_DECISION");}if(action!=Action.APPROVE&&action!=Action.REJECT)throw fail(400,"INVALID_DECISION");return write(r,id,id(body.operationId()),action,version(body.expectedVersion()),body.reason());}
    public Map<String,Object> withdraw(HttpServletRequest r,UUID id,WithdrawBody body){return write(r,id,id(body.operationId()),Action.WITHDRAW,version(body.expectedVersion()),body.reason());}
    private Map<String,Object> write(HttpServletRequest r,UUID id,UUID operation,Action action,long version,String reason){
        Actor actor=access.principal(r);var known=local().localOperation(actor,operation);if(known!=null&&!known.id().equals(id))throw fail(409,"OPERATION_BINDING_CONFLICT");Application app=local().local(actor.tenantId(),id);if(app==null)throw fail(404,"FEE_APPLICATION_NOT_FOUND");
        // Replays still verify the requested write-purpose CSRF; current object-read is rechecked by store before returning history.
        var prepared=known==null?access.prepare(r,actor,app.basis().ticketId(),app.basis().groupId(),app.basis().quoteId(),action,app):access.recovery(r,actor,app);
        var result=new FeeApprovalStore(source,prepared,Clock.systemUTC()).execute(actor,new Command(operation,id,action,version,app.basis(),reason));return view(result,List.of(),operation,true);
    }
    public Map<String,Object> detail(HttpServletRequest r,UUID id){Actor actor=access.principal(r);var app=local().local(actor.tenantId(),id);if(app==null)throw fail(404,"FEE_APPLICATION_NOT_FOUND");var prepared=access.prepare(r,actor,app.basis().ticketId(),app.basis().groupId(),app.basis().quoteId(),null,app);var verified=new FeeApprovalStore(source,prepared,Clock.systemUTC()).detail(actor,id);return view(verified,actions(verified,prepared),null,false);}
    public Map<String,Object> operation(HttpServletRequest r,UUID operation){Actor actor=access.principal(r);Application history=local().localOperation(actor,operation);if(history==null)throw fail(404,"FEE_OPERATION_NOT_FOUND");var app=local().local(actor.tenantId(),history.id());if(app==null)throw fail(503,"RECEIPT_APPLICATION_MISSING");var prepared=access.prepare(r,actor,app.basis().ticketId(),app.basis().groupId(),app.basis().quoteId(),null,app);return view(new FeeApprovalStore(source,prepared,Clock.systemUTC()).query(actor,operation),List.of(),operation,true);}
    public Map<String,Object> list(HttpServletRequest r,UUID ticket,int page,int size){Actor actor=access.principal(r);var items=new ArrayList<Object>();var candidates=local().localPage(actor.tenantId(),ticket,page,size);for(var app:candidates){try{var prepared=access.prepare(r,actor,ticket,app.basis().groupId(),app.basis().quoteId(),null,app);items.add(view(new FeeApprovalStore(source,prepared,Clock.systemUTC()).detail(actor,app.id()),actions(app,prepared),null,false));}catch(ApprovalFailure failure){if(failure.status()!=403&&failure.status()!=404)throw failure;}}return Map.of("items",items,"page",page,"size",size,"hasMore",items.size()==size);}
    private List<String> actions(Application app,FeeApprovalAuthority.Prepared prepared){try(var c=source.getConnection()){return FeeApprovalAuthority.assignmentMatches(c,app,prepared.ticket)?prepared.actions(app):List.of();}catch(SQLException e){throw fail(503,"DEPENDENCY_UNAVAILABLE");}}
    Map<String,Object> view(Application app,List<String> actions,UUID operation,boolean historical){
        var b=app.basis();var result=new LinkedHashMap<String,Object>();result.put("applicationId",app.id().toString());result.put("ticketId",b.ticketId().toString());result.put("roundId",b.roundId().toString());result.put("companyId",Long.toString(b.companyId()));result.put("groupId",b.groupId().toString());result.put("quoteId",b.quoteId().toString());result.put("quoteVersion",Long.toString(b.quoteVersion()));result.put("applicantUserId",Long.toString(app.applicantId()));result.put("state",app.state().name());result.put("version",Long.toString(app.version()));result.put("generation",Long.toString(b.generation()));result.put("chargesCustomer",b.chargesCustomer());result.put("financialReviewRequired",b.financialReviewRequired());result.put("basis",Map.of("qCents",Long.toString(b.q()),"cCents",Long.toString(b.c()),"hCents",Long.toString(b.h()),"bCents",Long.toString(b.b()),"thresholdCents",Long.toString(b.threshold()),"ruleVersion",b.ruleVersion(),"everRequiredFinance",b.everFinance()));
        result.put("customerConfirmation",app.confirmation()==null?null:Map.of("operationId",app.confirmation().id().toString(),"quoteId",app.confirmation().quoteId().toString(),"quoteVersion",Long.toString(app.confirmation().quoteVersion()),"quoteDigest",app.confirmation().quoteDigest()));result.put("operationId",operation==null?null:operation.toString());result.put("actions",actions);result.put("terminalReason",app.terminalReason());
        try(var c=source.getConnection()){
            String lines=FeeApprovalPersistence.scalar(c,"SELECT lines_json FROM fee_approval_binding WHERE tenant_id=? AND application_id=?",b.tenantId(),app.id());String coverage=FeeApprovalPersistence.scalar(c,"SELECT coverage_digest FROM fee_approval_binding WHERE tenant_id=? AND application_id=?",b.tenantId(),app.id());
            if(lines==null||coverage==null)throw fail(503,"FEE_BINDING_FACT_MISSING");result.put("lines",com.ias.trust.Chain.JSON.readValue(lines,List.class));result.put("coverageDigest",coverage);
            result.put("supervisorReceiptId",app.supervisorId()==null?null:FeeApprovalPersistence.scalar(c,"SELECT receipt_id FROM fee_approval_decision WHERE tenant_id=? AND application_id=? AND node='SUPERVISOR'",b.tenantId(),app.id()));
            result.put("financeReceiptId",app.financeId()==null?null:FeeApprovalPersistence.scalar(c,"SELECT receipt_id FROM fee_approval_decision WHERE tenant_id=? AND application_id=? AND node='FINANCE'",b.tenantId(),app.id()));
            result.put("completionId",app.state()!=State.COMPLETED?null:FeeApprovalPersistence.scalar(c,"SELECT completion_id FROM fee_approval_completion WHERE tenant_id=? AND application_id=?",b.tenantId(),app.id()));
            result.put("termination",app.state()==State.TERMINATED?FeeApprovalPersistence.termination(c,b.tenantId(),app.id()):null);
        }catch(SQLException e){throw fail(503,"DEPENDENCY_UNAVAILABLE");}return result;
    }
    static UUID id(String value){try{var id=UUID.fromString(value);if(!id.toString().equals(value))throw new Exception();return id;}catch(Exception e){throw fail(400,"INVALID_UUID");}}
    static long version(String value){try{long n=Long.parseLong(value);if(n<1||!Long.toString(n).equals(value))throw new Exception();return n;}catch(Exception e){throw fail(400,"INVALID_VERSION");}}
}


