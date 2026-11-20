package com.ias.gateway;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.context.annotation.*;
import org.springframework.web.servlet.function.*;
import org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions;
import com.ias.trust.*;
import java.util.*;

@SpringBootApplication
public class GatewayApplication {
 private final Map<String,long[]> rateWindows=new HashMap<>();
 private synchronized void rate(String remote,boolean write){long now=System.nanoTime();rateWindows.entrySet().removeIf(e->now-e.getValue()[0]>=60_000_000_000L);String key=remote+":"+write;long[] value=rateWindows.get(key);if((value==null&&rateWindows.size()>=4096)||(value!=null&&value[1]>=(write?300:1000)))throw new Problem(429,"RATE_LIMITED");if(value==null)rateWindows.put(key,new long[]{now,1});else value[1]++;}
 public static void main(String[] args){if(!Chain.active()||!Chain.role().equals("gateway"))throw new IllegalStateException("Gateway private configuration required");SpringApplication.run(GatewayApplication.class,args);}
 @Bean org.springframework.boot.web.servlet.FilterRegistrationBean<FileProxy> files(){var bean=new org.springframework.boot.web.servlet.FilterRegistrationBean<>(new FileProxy());bean.setOrder(-300);return bean;}
 @Bean RouterFunction<ServerResponse> routes(){return GatewayRouterFunctions.route("ias-static-boundary").route(r->true,this::proxy).build();}
 private ServerResponse proxy(ServerRequest input){
  var r=input.servletRequest();String path=r.getRequestURI(),method=r.getMethod();String trace=UUID.randomUUID().toString();long deadline=System.currentTimeMillis()+10000;
  Chain.begin(deadline);
  try{
   if(!allowed(method,path))throw new Problem(403,"ACCESS_DENIED");
   boolean asset=path.startsWith("/api/asset/"),ticket=path.startsWith("/api/ticket/"),finance=path.startsWith("/api/finance/"),approval=path.startsWith("/api/approval/"),stock=path.startsWith("/api/stock/"),notification=path.startsWith("/api/notification/"),write=!method.equals("GET");
   rate(r.getRemoteAddr(),write);
   if(write||path.equals("/api/auth/csrf"))Chain.source(r);
   if(stock&&path.startsWith("/api/stock/engineering/")){Chain.identity("gateway-identity",Chain.cookie(r),write?"TICKET_WORK":"TICKET_READ",r.getHeader("X-CSRF-TOKEN"),trace,deadline);}
   boolean reviewRead=finance&&!write&&path.matches("/api/finance/groups/[0-9a-f-]{36}/money/reviews/[0-9a-f-]{36}(?:/operations/[0-9a-f-]{36})?");
   if(reviewRead){
    try{Chain.identity("gateway-identity",Chain.cookie(r),"MONEY_READ",null,trace,deadline);}
    catch(Problem denied){if(denied.status!=403)throw denied;Chain.identity("gateway-identity",Chain.cookie(r),"FINANCE_READ",null,trace,deadline);}
   }
   if(finance&&!reviewRead||approval||(stock&&!path.startsWith("/api/stock/engineering/"))||notification){String purpose=finance?(path.matches("/api/finance/groups/[0-9a-f-]{36}/money/reviews/[0-9a-f-]{36}/confirm")?(write?"FINANCE_WRITE":"FINANCE_READ"):(path.contains("/money")?(write?"MONEY_WRITE":"MONEY_READ"):(path.matches("/api/finance/groups/[0-9a-f-]{36}/fee-execution-projection")?"FEE_READ":(write?"FINANCE_WRITE":"FINANCE_READ")))):approval?(path.startsWith("/api/approval/fees")?(write&&path.equals("/api/approval/fees")?"FEE_SUBMIT":"FEE_READ"):write?(path.endsWith("/decisions")?"APPROVAL_REVIEW":"APPROVAL_SUBMIT"):"APPROVAL_READ"):stock?(write?"STOCK_MANAGE":(path.startsWith("/api/stock/warehouse-catalog")||path.endsWith("/balances")||path.endsWith("/movements")||path.contains("/inbound-")||path.contains("/orders/")||path.contains("/execution-operations/"))?"STOCK_READ":"STOCK_CATALOG"):write?"NOTIFICATION_WRITE":"NOTIFICATION_READ";Chain.identity("gateway-identity",Chain.cookie(r),purpose,r.getHeader("X-CSRF-TOKEN"),trace,deadline);}
   if(asset){Chain.identity("gateway-identity",Chain.cookie(r),assetOperation(method,path),r.getHeader("X-CSRF-TOKEN"),trace,deadline);}
   if(ticket)Chain.identity("gateway-identity",Chain.cookie(r),path.matches("/api/ticket/tickets/[0-9a-f-]{36}/(?:fee-executions|fee-execution-operations/[0-9a-f-]{36})")?"FEE_READ":path.contains("/finance-vouchers")?(write?"MONEY_WRITE":"MONEY_READ"):path.endsWith("/fee-quote-references")?"FEE_READ":write?(path.matches("/api/ticket/tickets/[0-9a-f-]{36}/reopen-requests")?"TICKET_CUSTOMER_REOPEN":path.endsWith("/download-authorizations")?"TICKET_DOWNLOAD":path.matches("/api/ticket/tickets/[0-9a-f-]{36}/(?:cancel|cancellation-requests)")?"TICKET_CUSTOMER_CANCEL":path.matches("/api/ticket/tickets/[0-9a-f-]{36}/supplement-requests/[0-9a-f-]{36}/(?:reply|files/[0-9a-f-]{36})")?"TICKET_CUSTOMER_SUPPLEMENT":path.matches("/api/ticket/tickets/[0-9a-f-]{36}/solutions/[0-9a-f-]{36}/decision")?"TICKET_CUSTOMER_DECIDE":path.matches("/api/ticket/tickets/[0-9a-f-]{36}/.*")?"TICKET_WORK":"TICKET_CREATE"):"TICKET_READ",r.getHeader("X-CSRF-TOKEN"),trace,deadline);
   Map<String,String> headers=new LinkedHashMap<>();
   // A positive allow-list removes ALL untrusted Authorization/identity/Forwarded/delegation variants.
   for(String name:List.of("Cookie","Origin","Referer","Content-Type","Accept","X-CSRF-TOKEN","X-Login-Attempt-Id","Idempotency-Key")){
    var values=Collections.list(r.getHeaders(name));if(values.size()>1)throw new Problem(400,"VALIDATION_ERROR");if(!values.isEmpty())headers.put(name,values.getFirst());
   }
   byte[] body=r.getInputStream().readNBytes(32769);if(body.length>32768)throw new Problem(400,"VALIDATION_ERROR");
   String query=r.getQueryString();String target=path+(query==null?"":"?"+query);
   Chain.Reply reply=Chain.send(finance?"gateway-finance":approval?"gateway-approval":stock?"gateway-stock":notification?"gateway-notification":ticket?"gateway-ticket":asset?"gateway-asset":"gateway-identity",method,target,body,headers,trace,deadline);
   var out=ServerResponse.status(reply.status()).header("Cache-Control","no-store").header("X-Trace-Id",trace);
   for(String name:List.of("set-cookie","content-type","retry-after")){var values=reply.headers().get(name);if(values!=null)for(String v:values)out.header(name,v);}
   return out.body(reply.bytes());
  }catch(Problem e){return ServerResponse.status(e.status).header("Cache-Control","no-store").body(Map.of("code",e.code,"traceId",trace,"retryable",e.status==503));}
  catch(Exception e){return ServerResponse.status(503).header("Cache-Control","no-store").body(Map.of("code","DEPENDENCY_UNAVAILABLE","traceId",trace));}finally{Chain.end();}
 }
 static String assetOperation(String method,String path){
  boolean read=method.equals("GET");
  if(read&&path.matches("/api/asset/customer-companies/[1-9][0-9]*/members"))return "ASSET_READ";
  if(path.contains("/members")||path.contains("customer-enrollments"))return read?"PROVISION_READ":"PROVISION";
  return read?"ASSET_READ":"ASSET_WRITE";
 }
 static boolean allowed(String method,String path){
  if(path.indexOf('%')>=0||path.contains("..")||path.contains(";"))return false;
  if(method.equals("POST")&&(path.matches("/api/stock/warehouses/[1-9][0-9]*/orders/[0-9a-f-]{36}/(?:reserve|issue|return)")||path.matches("/api/stock/engineering/orders/[0-9a-f-]{36}/uses")))return true;
  if(method.equals("GET")&&(path.matches("/api/stock/warehouses/[1-9][0-9]*/(?:orders|execution-operations)/[0-9a-f-]{36}")||path.matches("/api/stock/engineering/(?:orders|execution-operations)/[0-9a-f-]{36}")))return true;
  if(method.equals("GET")&&path.matches("/api/stock/warehouse-catalog(?:/[1-9][0-9]*/parts)?"))return true;
  if(method.equals("GET")&&path.matches("/api/stock/warehouses/[1-9][0-9]*/(?:inbound-files/[0-9a-f-]{36}|inbound-operations/[0-9a-f-]{36})"))return true;
  if(method.equals("POST")&&path.matches("/api/stock/warehouses/[1-9][0-9]*/inbounds"))return true;
  if(method.equals("POST")&&path.matches("/api/ticket/tickets/[0-9a-f-]{36}/fee-executions"))return true;
  if(method.equals("GET")&&path.matches("/api/ticket/tickets/[0-9a-f-]{36}/fee-execution-operations/[0-9a-f-]{36}"))return true;
  if(method.equals("POST")&&path.matches("/api/ticket/tickets/[0-9a-f-]{36}/part-fee-executions"))return true;
  if(method.equals("GET")&&path.matches("/api/ticket/tickets/[0-9a-f-]{36}/part-fee-execution-operations/[0-9a-f-]{36}"))return true;
  if(method.equals("POST")&&path.matches("/api/ticket/tickets/[0-9a-f-]{36}/part-fee-execution-operations/[0-9a-f-]{36}/refuse"))return true;
  if(method.equals("GET")&&path.equals("/api/approval/fees/quote-context"))return true;
  if(method.equals("GET")&&path.matches("/api/approval/fees(?:/(?:operations/)?[0-9a-f-]{36})?"))return true;
  if(method.equals("POST")&&(path.equals("/api/approval/fees")||path.matches("/api/approval/fees/[0-9a-f-]{36}/(?:refresh|decisions|withdrawals)")))return true;
  if(method.equals("GET")&&path.matches("/api/finance/groups/[0-9a-f-]{36}/money(?:/entries|/operations/[0-9a-f-]{36})?"))return true;
  if(method.equals("GET")&&path.matches("/api/finance/groups/[0-9a-f-]{36}/fee-execution-projection"))return true;
  if(method.equals("POST")&&path.matches("/api/finance/groups/[0-9a-f-]{36}/money/(?:assess|lower|collect|reverse|refund|rebook)"))return true;
  if(method.equals("POST")&&path.matches("/api/finance/groups/[0-9a-f-]{36}/money/reviews(?:/[0-9a-f-]{36}/(?:confirm|approve|verify))?"))return true;
  if(method.equals("GET")&&path.matches("/api/finance/groups/[0-9a-f-]{36}/money/reviews/[0-9a-f-]{36}(?:/operations/[0-9a-f-]{36})?"))return true;
  if(method.equals("POST")&&path.matches("/api/ticket/tickets/[0-9a-f-]{36}/finance-vouchers"))return true;
  if(method.equals("GET")&&path.matches("/api/ticket/tickets/[0-9a-f-]{36}/finance-vouchers/[0-9a-f-]{36}"))return true;
  if(method.equals("PUT")&&path.matches("/api/ticket/tickets/[0-9a-f-]{36}/finance-vouchers/[0-9a-f-]{36}/content"))return true;
  if(method.equals("GET")&&path.matches("/api/finance/(?:tickets/[0-9a-f-]{36}/group|groups/[0-9a-f-]{36}/(?:quotes/[0-9a-f-]{36}|operations/[0-9a-f-]{36}|quote-sources/[0-9a-f-]{36}))"))return true;
  if(method.equals("POST")&&path.matches("/api/finance/(?:tickets/[0-9a-f-]{36}/group|groups/[0-9a-f-]{36}/(?:quotes(?:/[0-9a-f-]{36}/(?:confirm|reject|withdraw))?|quote-sources/[0-9a-f-]{36}/abort))"))return true;
  if(method.equals("GET")&&(path.matches("/api/approval/(?:access|parts(?:/(?:operations/)?[0-9a-f-]{36})?)")||path.matches("/api/stock/warehouses(?:/[1-9][0-9]*/(?:parts|balances|movements))?")||path.matches("/api/notification/(?:messages(?:/[0-9a-f-]{36})?|read-operations/[0-9a-f-]{36}|unread-count)")))return true;
  if(method.equals("POST")&&(path.equals("/api/approval/parts")||path.matches("/api/approval/parts/[0-9a-f-]{36}/(?:decisions|withdrawals)")||path.matches("/api/notification/messages/[0-9a-f-]{36}/read")))return true;
  if(method.equals("GET")&&path.matches("/api/ticket/(?:tickets/[0-9a-f-]{36}/(?:fee-quote-references|finance-quote-references|records|assignment-candidates|solutions|close-check|supplement-requests|cancellation-requests|transfer-requests|transfer-candidates|reopen-check|reopen-requests|reopen-candidates|rounds|rounds/[0-9a-f-]{36})|action-operations/[0-9a-f-]{36})"))return true;
  if(method.equals("POST")&&path.matches("/api/ticket/tickets/[0-9a-f-]{36}/(?:reopen-requests|reopen-requests/[0-9a-f-]{36}/decision|rounds/[0-9a-f-]{36}/responses|transfer-requests|transfer-requests/[0-9a-f-]{36}/(?:decision|withdraw)|cancel|cancellation-requests|cancellation-requests/[0-9a-f-]{36}/decision|accept|reject|assign|release-invalid-assignment|records|solutions|close|supplement-requests|supplement-requests/[0-9a-f-]{36}/reply|solutions/[0-9a-f-]{36}/decision|assignments/[0-9a-f-]{36}/accept)"))return true;
  if(method.equals("GET")&&path.matches("/api/ticket/tickets/[0-9a-f-]{36}/supplement-requests/[0-9a-f-]{36}/file-uploads/[0-9a-f-]{36}"))return true;
  if(method.equals("DELETE")&&path.matches("/api/ticket/tickets/[0-9a-f-]{36}/supplement-requests/[0-9a-f-]{36}/files/[0-9a-f-]{36}"))return true;
  if(method.equals("GET")&&path.matches("/api/ticket/transfer-inbox(?:/[0-9a-f-]{36})?"))return true;
  if(method.equals("GET")&&path.matches("/api/ticket/file-uploads/[0-9a-f-]{36}"))return true;
  if(method.equals("DELETE")&&path.matches("/api/ticket/files/[0-9a-f-]{36}"))return true;
  if(method.equals("POST")&&path.matches("/api/ticket/files/[0-9a-f-]{36}/download-authorizations"))return true;
  if(method.equals("GET")&&path.matches("/api/ticket/(?:tickets(?:/[0-9a-f-]{36})?|duplicate-candidates|create-operations/[0-9a-f-]{36})"))return true;
  if(method.equals("POST")&&(path.equals("/api/ticket/tickets")||path.matches("/api/ticket/create-operations/[0-9a-f-]{36}/resume")))return true;
  if(Set.of("GET","POST").contains(method)&&path.matches("/api/asset/customer-companies/[1-9][0-9]*/members/[1-9][0-9]*/role"))return true;
  if(method.equals("GET")&&path.matches("/api/asset/customer-companies/[1-9][0-9]*/membership-role-operations/[0-9a-f-]{36}"))return true;
  if(method.equals("GET"))return path.matches("/api/identity/employee-create-operations/[0-9a-f-]{36}")||path.matches("/api/auth/(csrf|me)")||path.matches("/api/identity/users(?:/[1-9][0-9]*(?:/access|/status-operations/[0-9a-f-]{36}|/scope-operations/[0-9a-f-]{36})?)?")||path.matches("/api/asset/(?:customer-companies|product-models|devices)(?:/[1-9][0-9]*(?:/members)?)?")||path.equals("/api/asset/me/customer-companies")||path.matches("/api/asset/(?:customer-enrollments|operations)/[0-9a-f-]{36}");
  if(method.equals("POST"))return path.equals("/api/identity/employees")||path.matches("/api/auth/(login|logout|password)")||path.matches("/api/identity/users/[1-9][0-9]*/(?:sessions/revoke|status|scope)")||path.matches("/api/asset/(customer-companies|product-models|devices)")||path.matches("/api/asset/(customer-companies|devices)/[1-9][0-9]*/status")||path.matches("/api/asset/customer-companies/[1-9][0-9]*/members")||path.matches("/api/asset/customer-enrollments/[0-9a-f-]{36}/resume");
  return method.equals("PATCH")&&path.matches("/api/asset/(customer-companies|product-models|devices)/[1-9][0-9]*");
 }
}











