package com.ias.ticket;
import com.ias.trust.*;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class StaffAccess {
 Map<String,Object> approvalFacts(Tickets.Actor a,String purpose){var body=new HashMap<String,Object>();body.put("sessionToken",a.token());body.put("purpose",purpose);if(a.csrf()!=null)body.put("csrfToken",a.csrf());var f=Chain.call("ticket-identity","/internal/identity/domain-authorizations",body,a.trace(),a.deadline());Chain.fresh(f,a.deadline());if(!Long.toString(a.tenant()).equals(f.get("tenantId"))||!Long.toString(a.user()).equals(f.get("userId")))throw new Problem(401,"SESSION_INVALID");return f;}
 Map<String,Object> facts(Tickets.Actor a){var body=new HashMap<String,Object>();body.put("sessionToken",a.token());var f=Chain.call("ticket-identity","/internal/identity/ticket-authorizations",body,a.trace(),a.deadline());Chain.fresh(f,a.deadline());return f;}
 boolean permission(Map<String,Object>f,String p){return ((List<?>)f.get("permissions")).contains(p);}
 boolean company(Map<String,Object>f,Object company){return permission(f,"ticket.read")&&((List<?>)f.get("companyIds")).contains(company.toString());}
 boolean engineer(Map<String,Object>f,Map<String,Object>row){return permission(f,"ticket.engineer.read")&&Boolean.TRUE.equals(f.get("departmentEnabled"))&&row.get("department_id")!=null&&row.get("department_id").toString().equals(f.get("departmentId"))&&row.get("engineer_user_id")!=null&&row.get("engineer_user_id").toString().equals(f.get("userId"))&&Set.of("ASSIGNED","IN_PROGRESS","WAITING_INFORMATION","PENDING_CUSTOMER_CONFIRMATION","RESOLVED","CLOSED","CANCELLED").contains(row.get("status"));}
 void customerService(Map<String,Object>f,Map<String,Object>row,String capability){if(!company(f,row.get("company_id")))throw new Problem(404,"TARGET_NOT_ACCESSIBLE");if(!permission(f,capability))throw new Problem(403,"ACCESS_DENIED");}
 void department(Map<String,Object>f,Object department){if(!((List<?>)f.get("assignableDepartmentIds")).contains(department.toString()))throw new Problem(404,"TARGET_NOT_ACCESSIBLE");}
 boolean target(Tickets.Actor a,Object user,Object department){var b=new HashMap<String,Object>();b.put("sessionToken",a.token());b.put("targetUserId",user.toString());b.put("departmentId",department.toString());var out=Chain.call("ticket-identity","/internal/identity/ticket-authorizations",b,a.trace(),a.deadline());Chain.fresh(out,a.deadline());return Boolean.TRUE.equals(out.get("eligible"));}
 String sql(Tickets.Actor a,List<Object>args){var f=facts(a);var clauses=new ArrayList<String>();var companies=(List<?>)f.get("companyIds");if(permission(f,"ticket.read")&&!companies.isEmpty()){clauses.add("r.company_id IN ("+String.join(",",Collections.nCopies(companies.size(),"?"))+")");companies.forEach(c->args.add(Tickets.id(c)));}if(permission(f,"ticket.engineer.read")&&Boolean.TRUE.equals(f.get("departmentEnabled"))&&f.get("departmentId")!=null){clauses.add("(r.engineer_user_id=? AND r.department_id=? AND r.status IN ('ASSIGNED','IN_PROGRESS','WAITING_INFORMATION','PENDING_CUSTOMER_CONFIRMATION','RESOLVED','CLOSED','CANCELLED'))");args.add(a.user());args.add(Tickets.id(f.get("departmentId")));}return clauses.isEmpty()?"1=0":"("+String.join(" OR ",clauses)+")";}
}
