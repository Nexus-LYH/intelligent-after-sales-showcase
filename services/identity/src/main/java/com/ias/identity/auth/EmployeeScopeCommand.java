package com.ias.identity.auth;

import tools.jackson.databind.JsonNode;
import java.util.*;

/** Strict, canonical employee scope input; management privileges cannot be delegated here. */
public record EmployeeScopeCommand(String operationId,long expectedSecurityVersion,Long departmentId,List<String> capabilities,List<String> companyIds,List<String> assignmentDepartmentIds,List<String> approvalDepartmentIds,List<String> reopenDepartmentIds,List<String> warehouseIds,String reason){
 private static final Set<String> GRANTABLE=Set.of("asset.read","asset.manage","identity.customer.provision","ticket.read","ticket.accept","ticket.reject","ticket.assign","ticket.engineer.read","ticket.assignment.accept","ticket.record","ticket.solution.submit","ticket.close","ticket.supplement.request","ticket.cancel.review","ticket.transfer.request","ticket.reopen.review","ticket.respond","approval.parts.read","approval.parts.submit","approval.parts.review","stock.read","stock.manage","finance.quote.read","finance.quote.write","approval.fees.read","approval.fees.submit","approval.fees.review","approval.fees.finance","finance.money.read","finance.money.assess","finance.money.adjust","finance.money.collect","finance.money.reverse","finance.money.refund");
 public static EmployeeScopeCommand parse(JsonNode b){
  if(!b.isObject()||b.size()!=10)throw bad();for(String key:List.of("operationId","expectedSecurityVersion","departmentId","capabilities","companyIds","assignmentDepartmentIds","approvalDepartmentIds","reopenDepartmentIds","warehouseIds","reason"))if(!b.has(key))throw bad();
  String operation=text(b.get("operationId")),expected=text(b.get("expectedSecurityVersion")),reason=text(b.get("reason"));try{if(!UUID.fromString(operation).toString().equals(operation))throw bad();}catch(IllegalArgumentException e){throw bad();}
  long version=number(expected);Long department=b.get("departmentId").isNull()?null:number(text(b.get("departmentId")));if(AuthCrypto.codePoints(reason)<5||AuthCrypto.codePoints(reason)>200||reason.chars().anyMatch(Character::isISOControl))throw bad();
  return new EmployeeScopeCommand(operation,version,department,capabilities(b.get("capabilities")),ids(b.get("companyIds")),ids(b.get("assignmentDepartmentIds")),ids(b.get("approvalDepartmentIds")),ids(b.get("reopenDepartmentIds")),ids(b.get("warehouseIds")),reason);
 }
 private static List<String> capabilities(JsonNode n){if(!n.isArray()||n.size()>GRANTABLE.size())throw bad();var values=new TreeSet<String>();for(var v:n){String x=text(v);if(!GRANTABLE.contains(x)||!values.add(x))throw bad();}return List.copyOf(values);}
 private static List<String> ids(JsonNode n){if(!n.isArray()||n.size()>100)throw bad();var values=new TreeSet<Long>();for(var v:n)if(!values.add(number(text(v))))throw bad();return values.stream().map(Object::toString).toList();}
 private static String text(JsonNode n){if(n==null||!n.isTextual())throw bad();return n.asText();}
 private static long number(String s){if(!s.matches("[1-9][0-9]{0,18}"))throw bad();try{return Long.parseLong(s);}catch(NumberFormatException e){throw bad();}}
 private static AuthFailure bad(){return new AuthFailure(400,"VALIDATION_ERROR",null);}
}
