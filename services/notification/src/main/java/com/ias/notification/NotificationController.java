package com.ias.notification;
import com.ias.trust.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
public final class NotificationController {
    private final MessageInbox inbox;private final NotificationAccess access;
    public NotificationController(MessageInbox inbox,NotificationAccess access){this.inbox=inbox;this.access=access;}
    @PostMapping("/internal/notification/events") public Map<String,Object> event(@RequestBody String text,HttpServletRequest r)throws Exception {
        if(!r.getParameterMap().isEmpty())throw bad();
        DomainEvent event;try{event=DomainEvent.decode(tools.jackson.databind.json.JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build().writeValueAsString(envelope(text)));}catch(Exception e){throw bad();}
        return inbox.receive(event,r);
    }
    @PostMapping("/internal/notification/suppressed-parts/repair") public Map<String,Object> repairPart(@RequestBody String text,HttpServletRequest request)throws Exception{
        if(!request.getParameterMap().isEmpty())throw bad();
        var body=map(text);
        if(!body.keySet().equals(Set.of("operationId","event","reason"))||!(body.get("event") instanceof Map)||!(body.get("reason") instanceof String reason)||!(body.get("operationId") instanceof String operation))throw bad();
        var event=DomainEvent.decode(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(body.get("event")));
        return inbox.repairSuppressedPart(UUID.fromString(uuid(operation)),event,reason,request);
    }
    @PostMapping("/internal/notification/fee-completions/repair") public Map<String,Object> repairFeeRecipients(@RequestBody String text,HttpServletRequest request)throws Exception{
        if(!request.getParameterMap().isEmpty())throw bad();
        var body=map(text);
        if(!body.keySet().equals(Set.of("operationId","event","recipientUserIds","reason"))||!(body.get("event") instanceof Map)||!(body.get("recipientUserIds") instanceof List<?> raw)||!(body.get("reason") instanceof String reason)||!(body.get("operationId") instanceof String operation))throw bad();
        var targets=new ArrayList<String>();
        for(Object target:raw){if(!(target instanceof String id))throw bad();targets.add(id);}
        var event=DomainEvent.decode(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(body.get("event")));
        return inbox.repairFeeRecipients(UUID.fromString(uuid(operation)),event,targets,reason,request);
    }
    @PostMapping("/internal/notification/finance-events") public Map<String,Object> financeEvent(@RequestBody String text,HttpServletRequest r)throws Exception {
        if(!r.getParameterMap().isEmpty())throw bad();
        try{var root=map(text);if(!root.keySet().equals(Set.of("eventType","payload"))||!(root.get("eventType") instanceof String type)||!(root.get("payload") instanceof Map<?,?> raw))throw bad();var payload=new LinkedHashMap<String,Object>();raw.forEach((k,v)->payload.put(Objects.toString(k),v));return inbox.receive(new MoneyFinalizedEvent(type,payload).domainEvent(),r);}catch(Problem p){throw p;}catch(Exception e){throw bad();}
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(String text){return tools.jackson.databind.json.JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build().readValue(text,Map.class);}
    @SuppressWarnings("unchecked") private static Object envelope(String text){var map=tools.jackson.databind.json.JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build().readValue(text,Map.class);if(!map.keySet().equals(Set.of("event"))||!(map.get("event") instanceof Map))throw bad();return map.get("event");}
    @GetMapping("/api/notification/messages") public Map<String,Object> list(HttpServletRequest r)throws Exception {
        query(r,Set.of("page","size"));int page=number(r.getParameter("page"),1),size=number(r.getParameter("size"),20);if(page>100000||!Set.of(20,50,100).contains(size))throw bad();return inbox.list(access.actor(r,false),page,size,r);
    }
    @GetMapping("/api/notification/messages/{id}") public Map<String,Object> detail(@PathVariable String id,HttpServletRequest r)throws Exception {query(r,Set.of());return inbox.detail(access.actor(r,false),uuid(id),r);}
    @PostMapping("/api/notification/messages/{id}/read") public Map<String,Object> read(@PathVariable String id,@RequestBody Map<String,Object> body,HttpServletRequest r)throws Exception {
        query(r,Set.of());if(!body.isEmpty())throw bad();var keys=Collections.list(r.getHeaders("Idempotency-Key"));if(keys.size()!=1)throw bad();String operation=uuid(keys.getFirst());return inbox.markRead(access.actor(r,true),uuid(id),operation,r);
    }
    @GetMapping("/api/notification/read-operations/{operation}") public Map<String,Object> operation(@PathVariable String operation,HttpServletRequest r)throws Exception {query(r,Set.of());return inbox.readOperation(access.actor(r,false),uuid(operation),r);}
    private static void query(HttpServletRequest r,Set<String> allowed){if(!allowed.containsAll(r.getParameterMap().keySet()))throw bad();for(String key:r.getParameterMap().keySet())if(r.getParameterValues(key).length!=1)throw bad();}
    private static int number(String value,int fallback){if(value==null)return fallback;try{if(!value.matches("[1-9][0-9]{0,5}"))throw bad();return Integer.parseInt(value);}catch(Exception e){throw bad();}}
    static String uuid(String value){try{String id=UUID.fromString(value).toString();if(!id.equals(value))throw bad();return id;}catch(Exception e){throw bad();}}
    private static Problem bad(){return new Problem(400,"VALIDATION_ERROR");}
    @ExceptionHandler(Problem.class) ResponseEntity<Map<String,Object>> problem(Problem failure){return response(failure.status,failure.code);}
    @ExceptionHandler({HttpMessageNotReadableException.class,IllegalArgumentException.class}) ResponseEntity<Map<String,Object>> invalid(Exception failure){return response(400,"VALIDATION_ERROR");}
    @ExceptionHandler(JdbcDelivery.DeliveryFailure.class) ResponseEntity<Map<String,Object>> conflict(JdbcDelivery.DeliveryFailure failure){String code=failure.getMessage();return response(code.equals("EVENT_ID_CONTENT_CONFLICT")?409:503,code);}
    @ExceptionHandler(Exception.class) ResponseEntity<Map<String,Object>> dependency(Exception failure){return response(503,"DEPENDENCY_UNAVAILABLE");}
    private ResponseEntity<Map<String,Object>> response(int status,String code){return ResponseEntity.status(status).header("Cache-Control","no-store").body(Map.of("code",code,"traceId",UUID.randomUUID().toString(),"retryable",status==503));}
}
