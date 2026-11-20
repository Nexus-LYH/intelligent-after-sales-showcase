package com.ias.approval;

import com.ias.trust.*;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.*;
import java.time.*;
import java.util.*;
import static com.ias.approval.FeeApproval.*;

/** All network reads occur BEFORE local generation/application locks. Prepared facts have a bounded deadline. */
final class FeeApprovalAuthority {
    @FunctionalInterface interface Calls {Map<String,Object> call(String edge,String path,Map<String,Object> body,HttpServletRequest request);}
    private final Calls calls;
    private org.springframework.jdbc.core.JdbcTemplate ownerDb;
    FeeApprovalAuthority(){this((e,p,b,r)->Chain.call(e,p,b,Chain.trace(r),Chain.deadline(r)));}
    FeeApprovalAuthority(Calls calls){this.calls=calls;}
    FeeApprovalAuthority(javax.sql.DataSource source){this();ownerDb=new org.springframework.jdbc.core.JdbcTemplate(source);}
    Actor principal(HttpServletRequest request){
        if(!Chain.active()||!request.isSecure()||!"approval".equals(Chain.role())||!"gateway-approval".equals(request.getAttribute("ias.chain.edge")))throw fail(403,"SERVICE_AUTHENTICATION_FAILED");
        var identity=identity(request,"FEE_READ",false);return new Actor(number(identity,"tenantId"),number(identity,"userId"));
    }
    private Map<String,Object> identity(HttpServletRequest r,String purpose,boolean write){
        var body=new LinkedHashMap<String,Object>();body.put("sessionToken",token(r));body.put("purpose",purpose);
        if(write){Chain.source(r);body.put("csrfToken",Chain.single(r,"X-CSRF-TOKEN"));}
        var identity=call("approval-identity","/internal/identity/domain-authorizations",body,r);
        if(!Boolean.TRUE.equals(identity.get("enabled"))||!"EMPLOYEE".equals(identity.get("identityType")))throw fail(403,"NOT_AUTHORIZED");return identity;
    }
    Prepared recovery(HttpServletRequest r,Actor actor,Application app){match(actor,identity(r,"FEE_RECOVER",true),"userId");return prepare(r,actor,app.basis().ticketId(),app.basis().groupId(),app.basis().quoteId(),null,app);}
    Prepared prepare(HttpServletRequest r,Actor actor,UUID ticketId,UUID groupId,UUID quoteId,Action action,Application old){
        boolean write=action!=null;
        String purpose=action==Action.APPROVE||action==Action.REJECT?(old!=null&&old.state()==State.WAITING_FINANCE?"FEE_FINANCE":"FEE_REVIEW"):write?"FEE_SUBMIT":"FEE_READ";
        var identity=identity(r,purpose,write);match(actor,identity,"userId");
        var body=Map.<String,Object>of("sessionToken",token(r),"ticketId",ticketId.toString(),"groupId",groupId.toString(),"quoteId",quoteId.toString());
        var finance=call("approval-finance","/internal/finance/fee-approval-context",body,r);var ticket=call("approval-ticket","/internal/ticket/fee-approval-context",body,r);
        match(actor,finance,"actorUserId");match(actor,ticket,"currentActorUserId");
        for(String key:List.of("companyId","ticketId","quoteId","groupId","quoteVersion","faultRelationId"))if(!Objects.equals(finance.get(key),ticket.get(key)))throw fail(503,"FEE_OWNER_BINDING_MISMATCH");
        if(!ticketId.toString().equals(ticket.get("ticketId"))||!quoteId.toString().equals(ticket.get("quoteId"))||!groupId.toString().equals(ticket.get("groupId")))throw fail(503,"FEE_OWNER_BINDING_MISMATCH");
        for(String key:List.of("sourceActorUserId","sourceOperationId","sourceCommandDigest","sourceResultDigest")){
            String wk=switch(key){case "sourceOperationId"->"operationId";case "sourceCommandDigest"->"commandDigest";case "sourceResultDigest"->"resultDigest";default->key;};
            if(!Objects.equals(finance.get(key),ticket.get(wk)))throw fail(503,"FEE_SOURCE_BINDING_MISMATCH");
        }
        if(!Objects.equals(ticket.get("actorUserId"),ticket.get("sourceActorUserId")))throw fail(503,"SOURCE_ACTOR_MISMATCH");
        var basis=new Basis(actor.tenantId(),number(finance,"companyId"),ticketId,uuid(finance,"roundId"),uuid(finance,"faultRelationId"),groupId,quoteId,number(finance,"quoteVersion"),number(finance,"basisGroupVersion"),number(ticket,"generation"),number0(finance,"qCents"),number0(finance,"cCents"),number0(finance,"hCents"),number0(finance,"bCents"),number0(finance,"thresholdCents"),text(finance,"ruleVersion"),bool(finance,"everRequiredFinance"),bool(finance,"financialReviewRequired"),bool(finance,"chargesCustomer"),hex(finance,"quoteDigest"),hex(ticket,"ticketFenceDigest"));
        if(!Objects.equals(finance.get("roundId"),ticket.get("quoteRoundId")))throw fail(503,"FEE_ROUND_BINDING_MISMATCH");
        Confirmation confirmation=null;
        if(finance.get("customerConfirmation")!=null){if(!(finance.get("customerConfirmation") instanceof Map<?,?> raw))throw fail(503,"INVALID_CONFIRMATION_PROOF");@SuppressWarnings("unchecked") var c=(Map<String,Object>)raw;confirmation=new Confirmation(uuid(c,"operationId"),uuid(c,"quoteId"),number(c,"quoteVersion"),hex(c,"quoteDigest"),basis.companyId());text(c,"actorUserId");Instant.parse(text(c,"confirmedAt"));}
        String applicant=old==null?(ticket.get("engineerUserId") instanceof String engineerId?engineerId:Long.toString(actor.userId())):Long.toString(old.applicantId());
        var recipients=call("approval-identity","/internal/identity/fee-recipients",Map.of("tenantId",Long.toString(actor.tenantId()),"companyId",Long.toString(basis.companyId()),"departmentId",text(ticket,"departmentId"),"applicantUserId",applicant),r);
        for(var e:Map.of("tenantId",Long.toString(actor.tenantId()),"companyId",Long.toString(basis.companyId()),"departmentId",text(ticket,"departmentId"),"applicantUserId",applicant).entrySet())if(!e.getValue().equals(recipients.get(e.getKey())))throw fail(503,"FEE_RECIPIENT_BINDING_MISMATCH");
        var supervisors=ids(recipients,"supervisorUserIds");var financeUsers=ids(recipients,"financeUserIds");
        if(supervisors.contains(applicant)||financeUsers.contains(applicant))throw fail(503,"INVALID_SELF_APPROVAL_CANDIDATE");
        boolean current=bool(finance,"current")&&bool(ticket,"currentQuote")&&basis.roundId().toString().equals(ticket.get("currentRoundId"));boolean acks=bool(ticket,"ownerAcksComplete")&&"COMMITTED".equals(ticket.get("sourceState"))&&"RELEASED".equals(ticket.get("coordinationState"));
        boolean engineer=bool(ticket,"callerIsCurrentEngineer")&&Long.toString(actor.userId()).equals(ticket.get("engineerUserId"))&&Boolean.TRUE.equals(recipients.get("applicantEligible"))&&"IN_PROGRESS".equals(text(ticket,"ticketStatus"));
        boolean active=Set.of("IN_PROGRESS","WAITING_INFORMATION").contains(text(ticket,"ticketStatus"));
        if(write){
            if(Set.of(Action.SUBMIT,Action.REFRESH,Action.WITHDRAW).contains(action)&&(!engineer||old!=null&&old.applicantId()!=actor.userId()))throw fail(403,"CURRENT_APPLICANT_ASSIGNMENT_REQUIRED");
            if((action==Action.APPROVE||action==Action.REJECT)&&!active)throw fail(409,"TICKET_NOT_APPROVABLE");
        }
        var lines=lines(finance);if("2".equals(finance.get("coverageVersion")))for(var line:lines)if(!basis.ticketId().toString().equals(line.get("ticketId"))||!basis.roundId().toString().equals(line.get("roundId")))throw fail(503,"FEE_OWNER_BINDING_MISMATCH");String coverage=FeeApprovalPersistence.sha(FeeApprovalPersistence.json(lines));
        // The cross-quote reservation and stock/ticket owner proofs do not yet exist.
        // Parsing a trusted v2 quote is useful for reads, but cannot authorize an approval write.
        if (write && "2".equals(finance.get("coverageVersion"))){FeePartApprovalBinding.claims(actor.tenantId(),basis.companyId(),basis.groupId().toString(),lines,finance.get("partApprovalClaims"));if(ownerDb!=null)FeePartApprovalBinding.verify(ownerDb,actor.tenantId(),actor.userId(),ticket,lines);for(var line:lines){if(!"PART".equals(line.get("kind")))continue;@SuppressWarnings("unchecked") var binding=(Map<String,Object>)line.get("partBinding");var catalog=call("approval-stock","/internal/stock/part-catalog-proof",Map.of("tenantId",Long.toString(actor.tenantId()),"warehouseId",binding.get("warehouseId"),"partId",binding.get("partId")),r);FeePartApprovalBinding.catalog(actor.tenantId(),binding,catalog);}}
        var facts=new Facts(basis,confirmation,current,acks,true,!supervisors.isEmpty(),!financeUsers.isEmpty(),supervisors.contains(Long.toString(actor.userId())),financeUsers.contains(Long.toString(actor.userId())));
        return new Prepared(actor,facts,finance,ticket,identity,recipients,lines,coverage,engineer,active,Chain.deadline(r));
    }
    final class Prepared implements FeeApprovalStore.Authority {
        final Actor actor;final Facts facts;final Map<String,Object> finance,ticket,identity,recipients;final List<Map<String,Object>> lines;final String coverage;final boolean engineer,active;final long deadline;
        Prepared(Actor actor,Facts facts,Map<String,Object> f,Map<String,Object> t,Map<String,Object> i,Map<String,Object> recipients,List<Map<String,Object>> lines,String coverage,boolean engineer,boolean active,long deadline){this.actor=actor;this.facts=facts;this.finance=f;this.ticket=t;this.identity=i;this.recipients=recipients;this.lines=lines;this.coverage=coverage;this.engineer=engineer;this.active=active;this.deadline=deadline;}
        void fresh(){for(var map:List.of(finance,ticket,identity,recipients))Chain.fresh(map,deadline);if(System.currentTimeMillis()>deadline)throw fail(503,"DEADLINE_EXCEEDED");}
        @Override public Facts verify(Connection c,Actor a,Command cmd,Application old)throws SQLException{
            fresh();if(!actor.equals(a)||!facts.basis().equals(cmd.basis()))throw fail(403,"FEE_ACTOR_BINDING_MISMATCH");
            if(old!=null&&old.state()==State.TERMINATED)throw fail(409,"TERMINAL_HISTORY_IMMUTABLE");
            if(old!=null&&!assignmentMatches(c,old,ticket))throw fail(409,"FEE_ASSIGNMENT_CHANGED");
            var threshold=FeeApprovalPersistence.scalar(c,"SELECT threshold_cents FROM approval_fee_rule_publication WHERE tenant_id=? AND rule_version=?",a.tenantId(),cmd.basis().ruleVersion());
            if(threshold==null||!Long.toString(cmd.basis().threshold()).equals(threshold))throw fail(409,"FROZEN_RULE_PUBLICATION_MISMATCH");
            var local=FeeApprovalPersistence.object(c,"SELECT binding_json FROM approval_finance_fence WHERE tenant_id=? AND freeze_id=?",a.tenantId(),text(ticket,"freezeId"));
            if(local==null)throw fail(409,"OWNER_FENCE_NOT_OBSERVED");
            for(String key:ApprovalFinanceController.FIELDS)if(!Objects.equals(local.get(key),ticket.get(key)))throw fail(409,"OWNER_FENCE_BINDING_MISMATCH");
            var terminal=FeeApprovalPersistence.scalar(c,"SELECT source_state FROM approval_finance_terminal WHERE tenant_id=? AND freeze_id=?",a.tenantId(),text(ticket,"freezeId"));
            var result=FeeApprovalPersistence.scalar(c,"SELECT result_digest FROM approval_finance_terminal WHERE tenant_id=? AND freeze_id=?",a.tenantId(),text(ticket,"freezeId"));
            if(!"COMMITTED".equals(terminal)||!Objects.equals(result,ticket.get("resultDigest")))throw fail(409,"OWNER_TERMINAL_NOT_COMMITTED");
            return facts;
        }
        @Override public void read(Connection c,Actor a,Application app){fresh();if(!actor.equals(a)||a.tenantId()!=app.basis().tenantId()||!app.basis().ticketId().toString().equals(ticket.get("ticketId"))||!app.basis().quoteId().toString().equals(finance.get("quoteId")))throw fail(404,"FEE_APPLICATION_NOT_ACCESSIBLE");}
        @Override public void persisted(Connection c,Actor a,Command cmd,Application previous,Application next,UUID event,String digest)throws SQLException{FeeApprovalPersistence.persisted(c,a,cmd,previous,next,event,finance,ticket,lines,coverage);}
        List<String> actions(Application app){fresh();if(!facts.currentQuote()||!facts.ownerAcksComplete()||app.state()==State.TERMINATED)return List.of();var actions=new ArrayList<String>();if(engineer&&app.applicantId()==actor.userId()&&!Set.of(State.COMPLETED,State.REJECTED,State.WITHDRAWN).contains(app.state())){actions.add("withdraw");if(Set.of(State.WAITING_CUSTOMER,State.WAITING_CONFIGURATION).contains(app.state()))actions.add("refresh");}if(active&&app.applicantId()!=actor.userId()&&(app.state()==State.PENDING_SUPERVISOR&&facts.actorSupervisor()||app.state()==State.WAITING_FINANCE&&facts.actorFinance())){actions.add("approve");actions.add("reject");}return List.copyOf(actions);}
    }
    static boolean assignmentMatches(Connection c,Application app,Map<String,Object> current)throws SQLException{
        var original=FeeApprovalPersistence.object(c,"SELECT ticket_context FROM fee_approval_binding WHERE tenant_id=? AND application_id=?",app.basis().tenantId(),app.id());
        if(original==null)return false;for(String key:List.of("departmentId","assignmentId","engineerUserId","currentRoundId"))if(!Objects.equals(original.get(key),current.get(key)))return false;return true;
    }
    private Map<String,Object> call(String e,String p,Map<String,Object>b,HttpServletRequest r){try{var result=calls.call(e,p,b,r);if(result==null)throw fail(503,"FEE_AUTHORITY_UNAVAILABLE");Chain.fresh(result,Chain.deadline(r));return result;}catch(Problem problem){throw fail(problem.status,problem.code);}}
    static String token(HttpServletRequest r){String token=Chain.cookie(r);if(token==null||token.isBlank())throw fail(401,"SESSION_INVALID");return token;}
    static String text(Map<String,Object> m,String key){if(!(m.get(key) instanceof String s)||s.isBlank())throw fail(503,"INVALID_FEE_OWNER_FACT");return s;}
    static long number(Map<String,Object>m,String key){long n=number0(m,key);if(n==0)throw fail(503,"INVALID_FEE_OWNER_FACT");return n;}
    static long number0(Map<String,Object>m,String key){String s=text(m,key);try{long n=Long.parseLong(s);if(n<0||!Long.toString(n).equals(s))throw new Exception();return n;}catch(Exception e){throw fail(503,"INVALID_FEE_OWNER_FACT");}}
    static UUID uuid(Map<String,Object>m,String key){String s=text(m,key);try{UUID id=UUID.fromString(s);if(!id.toString().equals(s))throw new Exception();return id;}catch(Exception e){throw fail(503,"INVALID_FEE_OWNER_FACT");}}
    static String hex(Map<String,Object>m,String key){String s=text(m,key);if(!s.matches("[0-9a-f]{64}"))throw fail(503,"INVALID_FEE_OWNER_FACT");return s;}
    static boolean bool(Map<String,Object>m,String key){if(!(m.get(key) instanceof Boolean b))throw fail(503,"INVALID_FEE_OWNER_FACT");return b;}
    static void match(Actor a,Map<String,Object>m,String key){if(!Long.toString(a.tenantId()).equals(m.get("tenantId"))||!Long.toString(a.userId()).equals(m.get(key)))throw fail(401,"SESSION_INVALID");}
    static Set<String> ids(Map<String,Object>m,String key){if(!(m.get(key) instanceof List<?> list))throw fail(503,"INVALID_FEE_RECIPIENTS");var ids=new HashSet<String>();for(Object value:list){if(!(value instanceof String id)||!id.matches("[1-9][0-9]{0,18}")||!ids.add(id))throw fail(503,"INVALID_FEE_RECIPIENTS");Long.parseLong(id);}return Set.copyOf(ids);}
    @SuppressWarnings("unchecked")static List<Map<String,Object>> lines(Map<String,Object>m){
        Object version=m.get("coverageVersion");if(version!=null&&!"2".equals(version))throw fail(503,"INVALID_FEE_COVERAGE");
        if(!(m.get("lines") instanceof List<?> list)||list.isEmpty())throw fail(503,"INVALID_FEE_COVERAGE");
        var result=new TreeMap<String,Map<String,Object>>();var approvals=new HashSet<String>();boolean part=false;
        for(Object value:list){if(!(value instanceof Map<?,?> raw))throw fail(503,"INVALID_FEE_COVERAGE");var line=(Map<String,Object>)raw;
            String kind=text(line,"kind");boolean bound="2".equals(version)&&"PART".equals(kind);
            if(!line.keySet().equals(bound?Set.of("id","ticketId","roundId","amountCents","kind","partBinding"):Set.of("id","ticketId","roundId","amountCents","kind")))throw fail(503,"INVALID_FEE_COVERAGE");
            String id=uuid(line,"id").toString();uuid(line,"ticketId");uuid(line,"roundId");number(line,"amountCents");
            if(!Set.of("LABOR","PART","OTHER").contains(kind))throw fail(503,"INVALID_FEE_COVERAGE");
            var canonical=new TreeMap<String,Object>(line);
            if(bound){part=true;if(!(line.get("partBinding") instanceof Map<?,?> bindingRaw))throw fail(503,"INVALID_FEE_COVERAGE");var b=(Map<String,Object>)bindingRaw;
                if(!b.keySet().equals(Set.of("approvalId","approvalVersion","warehouseId","partId","quantity","unitCode","allocationRule")))throw fail(503,"INVALID_FEE_COVERAGE");
                if(!approvals.add(uuid(b,"approvalId").toString()))throw fail(503,"INVALID_FEE_COVERAGE");for(String key:List.of("approvalVersion","warehouseId","partId","quantity"))number(b,key);
                String unit=text(b,"unitCode");if(!unit.equals(unit.strip())||unit.length()>20||unit.chars().anyMatch(Character::isISOControl)||!"FULL_LINE_SINGLE_USE_V1".equals(text(b,"allocationRule")))throw fail(503,"INVALID_FEE_COVERAGE");canonical.put("partBinding",Collections.unmodifiableMap(new TreeMap<>(b)));}
            if(result.put(id,Collections.unmodifiableMap(canonical))!=null)throw fail(503,"INVALID_FEE_COVERAGE");
        }if("2".equals(version)&&!part)throw fail(503,"INVALID_FEE_COVERAGE");return List.copyOf(result.values());}
}
