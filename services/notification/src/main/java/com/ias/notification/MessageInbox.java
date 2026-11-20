package com.ias.notification;
import com.ias.trust.Problem;
import jakarta.servlet.http.HttpServletRequest;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import com.ias.trust.Chain;
import java.nio.charset.StandardCharsets;

/** The inbox marker and actual recipient messages commit together in the notification owner's database. */
public final class MessageInbox {
    private final JdbcDelivery delivery;private final NotificationAccess access;
    public MessageInbox(DataSource source,NotificationAccess access){this.delivery=new JdbcDelivery(source,6,Duration.ofSeconds(30));this.access=Objects.requireNonNull(access);}
    public Map<String,Object> receive(DomainEvent event,HttpServletRequest request)throws Exception {
        FeeEventClassifier.validate(event);
        var recipients=access.recipients(event,request);var unique=new LinkedHashMap<Long,NotificationAccess.Recipient>();for(var recipient:recipients)unique.putIfAbsent(recipient.user(),recipient);
        if(unique.size()>1000)throw new Problem(503,"RECIPIENT_LIMIT");
        var ack=delivery.consume("notification",event.producer(),event,(c,e)->{
            if(fee(e)&&!acceptFeeOrder(c,e))return;
            persistMessages(c,e,unique.values());
        });
        return Map.of("eventId",event.eventId().toString(),"ack",ack.name());
    }
    public Map<String,Object> repairSuppressedPart(UUID operation,DomainEvent event,String reason,HttpServletRequest request)throws Exception{
        if(!request.isSecure()||!"stock-notification".equals(request.getAttribute("ias.chain.edge")))throw new Problem(403,"SERVICE_AUTHENTICATION_FAILED");
        if(FeeEventClassifier.validate(event)!=FeeEventClassifier.Kind.PART||reason==null||reason.isBlank()||reason.length()>500)throw new Problem(400,"INVALID_REPAIR");
        var recipients=new LinkedHashMap<Long,NotificationAccess.Recipient>();
        for(var recipient:access.recipients(event,request))recipients.putIfAbsent(recipient.user(),recipient);
        if(recipients.isEmpty()||recipients.size()>1000)throw new Problem(409,"REPAIR_RECIPIENTS_UNAVAILABLE");
        return delivery.transaction(connection->{
            long tenant=Long.parseLong(event.tenantId());
            execute(connection,"INSERT INTO notification_suppression_repair_lock VALUES(?,?,?) ON DUPLICATE KEY UPDATE created_at=created_at",tenant,event.eventId(),Timestamp.from(now(connection)));
            rows(connection,"SELECT tenant_id FROM notification_suppression_repair_lock WHERE tenant_id=? AND event_id=? FOR UPDATE",tenant,event.eventId());
            var consumed=rows(connection,"SELECT tenant_id,envelope_sha FROM event_inbox WHERE consumer_id='notification' AND event_id=?",event.eventId());
            if(consumed.size()!=1||((Number)consumed.getFirst().get("tenant_id")).longValue()!=tenant||!event.sha().equals(text(consumed.getFirst(),"envelope_sha")))throw new Problem(409,"REPAIR_EVENT_MISMATCH");
            var prior=rows(connection,"SELECT tenant_id,event_id,reason,restored_messages FROM notification_suppression_repair WHERE operation_id=?",operation);
            if(!prior.isEmpty()){
                var saved=prior.getFirst();
                if(((Number)saved.get("tenant_id")).longValue()!=tenant||!event.eventId().toString().equals(text(saved,"event_id"))||!reason.equals(text(saved,"reason")))throw new Problem(409,"IDEMPOTENCY_CONFLICT");
                return Map.of("operationId",operation.toString(),"eventId",event.eventId().toString(),"restoredMessages",saved.get("restored_messages"));
            }
            var suppression=rows(connection,"SELECT reason FROM fee_notification_suppression WHERE event_id=? AND tenant_id=?",event.eventId(),tenant);
            if(suppression.size()!=1||!"OUT_OF_ORDER_VERSION".equals(text(suppression.getFirst(),"reason")))throw new Problem(409,"EVENT_NOT_REPAIRABLE");
            if(!rows(connection,"SELECT id FROM notification_message WHERE tenant_id=? AND event_id=?",tenant,event.eventId()).isEmpty())throw new Problem(409,"EVENT_ALREADY_DELIVERED");
            persistMessages(connection,event,recipients.values());
            execute(connection,"INSERT INTO notification_suppression_repair VALUES(?,?,?,?,?,?,?,?)",operation,tenant,event.eventId(),event.producer(),event.sha(),reason,recipients.size(),Timestamp.from(now(connection)));
            return Map.of("operationId",operation.toString(),"eventId",event.eventId().toString(),"restoredMessages",recipients.size());
        });
    }
    public Map<String,Object> repairFeeRecipients(UUID operation,DomainEvent event,List<String> targets,String reason,HttpServletRequest request)throws Exception{
        if(!request.isSecure()||!"approval-notification".equals(request.getAttribute("ias.chain.edge")))throw new Problem(403,"SERVICE_AUTHENTICATION_FAILED");
        if(FeeEventClassifier.validate(event)!=FeeEventClassifier.Kind.APPROVAL||operation==null||targets==null||targets.isEmpty()||targets.size()>1000||reason==null||reason.isBlank()||reason.length()>500)throw new Problem(400,"INVALID_REPAIR");
        var requested=new TreeSet<Long>();
        for(String target:targets){
            if(target==null||!target.matches("[1-9][0-9]{0,18}"))throw new Problem(400,"INVALID_REPAIR");
            try{if(!requested.add(Long.parseLong(target)))throw new Problem(400,"INVALID_REPAIR");}
            catch(NumberFormatException failure){throw new Problem(400,"INVALID_REPAIR");}
        }
        var eligible=new LinkedHashMap<Long,NotificationAccess.Recipient>();
        for(var recipient:access.recipients(event,request))eligible.putIfAbsent(recipient.user(),recipient);
        if(!eligible.keySet().containsAll(requested))throw new Problem(403,"REPAIR_RECIPIENT_NOT_ELIGIBLE");
        String targetText=String.join(",",requested.stream().map(Object::toString).toList());
        return delivery.transaction(connection->{
            long tenant=Long.parseLong(event.tenantId());
            execute(connection,"INSERT INTO notification_suppression_repair_lock VALUES(?,?,?) ON DUPLICATE KEY UPDATE created_at=created_at",tenant,event.eventId(),Timestamp.from(now(connection)));
            rows(connection,"SELECT tenant_id FROM notification_suppression_repair_lock WHERE tenant_id=? AND event_id=? FOR UPDATE",tenant,event.eventId());
            var consumed=rows(connection,"SELECT tenant_id,envelope_sha FROM event_inbox WHERE consumer_id='notification' AND event_id=?",event.eventId());
            if(consumed.size()!=1||((Number)consumed.getFirst().get("tenant_id")).longValue()!=tenant||!event.sha().equals(text(consumed.getFirst(),"envelope_sha")))throw new Problem(409,"REPAIR_EVENT_MISMATCH");
            var prior=rows(connection,"SELECT tenant_id,event_id,envelope_sha,requested_recipients,reason,restored_messages FROM notification_fee_recipient_repair WHERE operation_id=?",operation);
            if(!prior.isEmpty()){
                var saved=prior.getFirst();
                if(((Number)saved.get("tenant_id")).longValue()!=tenant||!event.eventId().toString().equals(text(saved,"event_id"))||!event.sha().equals(text(saved,"envelope_sha"))||!targetText.equals(text(saved,"requested_recipients"))||!reason.equals(text(saved,"reason")))throw new Problem(409,"IDEMPOTENCY_CONFLICT");
                return Map.of("operationId",operation.toString(),"eventId",event.eventId().toString(),"restoredMessages",saved.get("restored_messages"));
            }
            var existing=new HashSet<Long>();
            for(var saved:rows(connection,"SELECT recipient_user_id FROM notification_message WHERE tenant_id=? AND event_id=?",tenant,event.eventId()))existing.add(((Number)saved.get("recipient_user_id")).longValue());
            var missing=new ArrayList<NotificationAccess.Recipient>();
            for(long user:requested)if(!existing.contains(user))missing.add(eligible.get(user));
            persistMessages(connection,event,missing);
            execute(connection,"INSERT INTO notification_fee_recipient_repair VALUES(?,?,?,?,?,?,?,?)",operation,tenant,event.eventId(),event.sha(),targetText,reason,missing.size(),Timestamp.from(now(connection)));
            return Map.of("operationId",operation.toString(),"eventId",event.eventId().toString(),"restoredMessages",missing.size());
        });
    }
    private static void persistMessages(Connection c,DomainEvent e,Collection<NotificationAccess.Recipient> recipients)throws SQLException{
            for(var recipient:recipients){
                String id=UUID.nameUUIDFromBytes(("notification-message:"+e.tenantId()+":"+recipient.user()+":"+e.eventId()).getBytes(StandardCharsets.UTF_8)).toString();
                String title=title(e),body=customer(e)?customerBody(e):"请打开申请核验当前状态与权限；通知不代替审批或库存执行。";
                execute(c,"INSERT INTO notification_message(id,tenant_id,recipient_user_id,event_id,recipient_kind,event_type,owner_event_json,approval_id,title,body,occurred_at,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",id,Long.parseLong(e.tenantId()),recipient.user(),e.eventId().toString(),recipient.kind(),e.type(),e.encode(),e.aggregateId(),title,body,Timestamp.from(e.occurredAt()),Timestamp.from(now(c)));
                if(fee(e)){
                    execute(c,"UPDATE notification_message SET object_type=?,object_id=?,scope_generation=? WHERE id=?","FEE",object(e),generation(e),id);
                    execute(c,"INSERT INTO notification_email_capture VALUES(?,?,?,?,?,?,?,?)",id,Long.parseLong(e.tenantId()),recipient.user(),e.eventId(),"user-"+recipient.user()+"@local.invalid",title,"本地捕获：请登录站内信核验当前费用状态与权限。",Timestamp.from(now(c)));
                }else if(customer(e)){
                    execute(c,"UPDATE notification_message SET object_type='TICKET',object_id=? WHERE id=?",e.aggregateId(),id);
                    execute(c,"INSERT INTO notification_email_capture VALUES(?,?,?,?,?,?,?,?)",id,Long.parseLong(e.tenantId()),recipient.user(),e.eventId(),"user-"+recipient.user()+"@local.invalid",title,"本地捕获："+body,Timestamp.from(now(c)));
                }
            }
    }
    public Map<String,Object> list(NotificationAccess.Actor actor,int page,int size,HttpServletRequest request)throws Exception {
        return delivery.transaction(c->{
            var candidates=rows(c,"SELECT m.*,r.read_at FROM notification_message m LEFT JOIN notification_message_read r ON r.message_id=m.id AND r.tenant_id=m.tenant_id AND r.recipient_user_id=m.recipient_user_id WHERE m.tenant_id=? AND m.recipient_user_id=? ORDER BY m.created_at DESC,m.id DESC LIMIT 1001",actor.tenant(),actor.user());
            if(candidates.size()>1000)throw new Problem(503,"MESSAGE_QUERY_LIMIT");
            var visible=new ArrayList<Map<String,Object>>();var limit=new Semaphore(12);long deadline=Chain.deadline(request);
            try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
                var pending=new ArrayList<Future<Boolean>>();
                for(var row:candidates)pending.add(pool.submit(()->{limit.acquire();try{Chain.begin(deadline);return access.visible(actor,event(row),text(row,"recipient_kind"),request);}finally{Chain.end();limit.release();}}));
                for(int i=0;i<candidates.size();i++)try{if(pending.get(i).get())visible.add(project(candidates.get(i)));}catch(ExecutionException failure){Throwable cause=failure.getCause();if(cause instanceof Exception e)throw e;throw new IllegalStateException(cause);}
            }
            int from=Math.min((page-1)*size,visible.size()),to=Math.min(from+size,visible.size());return Map.of("items",List.copyOf(visible.subList(from,to)),"total",visible.size(),"page",page,"size",size);
        });
    }
    public Map<String,Object> detail(NotificationAccess.Actor actor,String id,HttpServletRequest request)throws Exception {return delivery.transaction(c->project(owned(c,actor,id,request,false)));}
    public Map<String,Object> markRead(NotificationAccess.Actor actor,String id,String operation,HttpServletRequest request)throws Exception {
        return delivery.transaction(c->{
            // Serialize all original read keys for this actor, including a key raced against two messages.
            // Immutable message/outbox facts require no UPDATE privilege.
            execute(c,"INSERT INTO notification_reader_lock(tenant_id,actor_user_id,created_at) VALUES(?,?,?) ON DUPLICATE KEY UPDATE created_at=created_at",actor.tenant(),actor.user(),Timestamp.from(now(c)));
            rows(c,"SELECT actor_user_id FROM notification_reader_lock WHERE tenant_id=? AND actor_user_id=? FOR UPDATE",actor.tenant(),actor.user());
            // First consistent table read occurs after the actor lock, including under MySQL REPEATABLE READ.
            // A rejected current object rolls back a newly inserted actor lock as well.
            owned(c,actor,id,request,false);
            var prior=rows(c,"SELECT message_id,read_at FROM notification_read_operation WHERE tenant_id=? AND actor_user_id=? AND operation_id=?",actor.tenant(),actor.user(),operation);
            if(!prior.isEmpty()){if(!id.equals(text(prior.getFirst(),"message_id")))throw new Problem(409,"IDEMPOTENCY_CONFLICT");return readResult(operation,id,prior.getFirst().get("read_at"));}
            Instant at=now(c);
            execute(c,"INSERT INTO notification_message_read(tenant_id,recipient_user_id,message_id,first_operation_id,read_at) VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE read_at=read_at",actor.tenant(),actor.user(),id,operation,Timestamp.from(at));
            var first=rows(c,"SELECT read_at FROM notification_message_read WHERE tenant_id=? AND recipient_user_id=? AND message_id=? FOR UPDATE",actor.tenant(),actor.user(),id).getFirst();
            execute(c,"INSERT INTO notification_read_operation VALUES(?,?,?,?,?)",actor.tenant(),actor.user(),operation,id,first.get("read_at"));
            execute(c,"INSERT INTO notification_read_audit VALUES(?,?,?,?,?)",actor.tenant(),actor.user(),operation,id,Timestamp.from(at));
            return readResult(operation,id,first.get("read_at"));
        });
    }
    public Map<String,Object> readOperation(NotificationAccess.Actor actor,String operation,HttpServletRequest request)throws Exception {
        return delivery.transaction(c->{var found=rows(c,"SELECT message_id,read_at FROM notification_read_operation WHERE tenant_id=? AND actor_user_id=? AND operation_id=?",actor.tenant(),actor.user(),operation);if(found.isEmpty())throw new Problem(404,"OPERATION_NOT_OBSERVED");var row=found.getFirst();String id=text(row,"message_id");owned(c,actor,id,request,false);return readResult(operation,id,row.get("read_at"));});
    }
    private Map<String,Object> owned(Connection c,NotificationAccess.Actor actor,String id,HttpServletRequest request,boolean unused)throws Exception {
        var found=rows(c,"SELECT m.*,r.read_at FROM notification_message m LEFT JOIN notification_message_read r ON r.message_id=m.id AND r.tenant_id=m.tenant_id AND r.recipient_user_id=m.recipient_user_id WHERE m.id=? AND m.tenant_id=? AND m.recipient_user_id=?",id,actor.tenant(),actor.user());
        if(found.isEmpty()||!access.visible(actor,event(found.getFirst()),text(found.getFirst(),"recipient_kind"),request))throw new Problem(404,"TARGET_NOT_ACCESSIBLE");return found.getFirst();
    }
    private static DomainEvent event(Map<String,Object> row){try{return DomainEvent.decode(text(row,"owner_event_json"));}catch(Exception e){throw new Problem(503,"MESSAGE_FACT_INVALID");}}
    private static Map<String,Object> project(Map<String,Object> row){var out=new LinkedHashMap<String,Object>();out.put("id",text(row,"id"));out.put("type",text(row,"event_type"));out.put("title",text(row,"title"));out.put("body",text(row,"body"));out.put("createdAt",utc(row.get("created_at")));out.put("occurredAt",utc(row.get("occurred_at")));out.put("read",row.get("read_at")!=null);out.put("readAt",row.get("read_at")==null?null:utc(row.get("read_at")));String id=row.get("object_id")==null?text(row,"approval_id"):text(row,"object_id"),type=row.get("object_type")==null?"APPROVAL_PARTS":text(row,"object_type");out.put("object",Map.of("type",type,"id",id,"route",type.equals("FEE")?"/fees/"+id:type.equals("TICKET")?"/tickets/"+id:"/approvals/parts/"+id));return out;}
    private static boolean fee(DomainEvent e){return FeeEventClassifier.validate(e)!=FeeEventClassifier.Kind.NONE;}
    private static boolean customer(DomainEvent e){return "ticket".equals(e.producer())&&Set.of("ticket.accepted","ticket.resolution_submitted","ticket.closed").contains(e.type());}
    private static String customerBody(DomainEvent e){return "ticket.resolution_submitted".equals(e.type())?"工程师已提交维修方案，请登录确认。":"ticket.closed".equals(e.type())?"您的报修已完成并关闭，可登录查看历史。":"您的报修已受理，可在工单中查看后续进度。";}
    private static String object(DomainEvent e){return e.payload().getOrDefault("feeGroupId",e.payload().getOrDefault("groupId",e.aggregateId()));}
    private static long generation(DomainEvent e){try{return Long.parseLong(e.payload().getOrDefault("generation",e.payload().getOrDefault("accountVersion","0")));}catch(Exception failure){throw new Problem(400,"INVALID_FEE_EVENT");}}
    private static String title(DomainEvent e){return switch(e.type()){case "approval.fee_completed"->"费用审批已完成";case "ticket.fee_execution_recorded"->"收费执行已登记";case "inventory.consumed"->"PART收费执行已登记";case "finance.refund_finalized"->"退款已核销";case "finance.collection_finalized"->switch(e.payload().get("action")){case "COLLECTION_REVERSED"->"收款错录已冲正";case "COLLECTION_REBOOKED"->"收款已冲正重记";default->"线下收款已登记";};case "ticket.accepted"->"报修已受理";case "ticket.resolution_submitted"->"维修方案待确认";case "ticket.closed"->"维修已完成关闭";case "approval.submitted"->"备件申请待审批";case "approval.approved"->"备件申请已批准";case "approval.rejected"->"备件申请已拒绝";case "approval.withdrawn"->"备件申请已撤回";default->"备件申请状态已更新";};}
    private static boolean acceptFeeOrder(Connection connection,DomainEvent event)throws SQLException{
        long tenant=Long.parseLong(event.tenantId());
        long generation=event.producer().equals("finance")?0:generation(event);
        String subject=object(event);
        if(!event.producer().equals("finance")){
            execute(connection,"INSERT INTO fee_authorization_notification_cursor VALUES(?,?,?) ON DUPLICATE KEY UPDATE generation=generation",tenant,subject,generation);
            var authorization=rows(connection,"SELECT generation FROM fee_authorization_notification_cursor WHERE tenant_id=? AND subject_id=? FOR UPDATE",tenant,subject).getFirst();
            long known=((Number)authorization.get("generation")).longValue();
            if(generation<known)return suppressFeeEvent(connection,event,"STALE_GENERATION");
            if(generation>known)execute(connection,"UPDATE fee_authorization_notification_cursor SET generation=? WHERE tenant_id=? AND subject_id=?",generation,tenant,subject);
        }
        var version=new java.math.BigInteger(event.aggregateVersion());
        execute(connection,"INSERT INTO fee_event_notification_cursor VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE generation=generation",tenant,event.producer(),event.aggregateId(),generation,version);
        var stream=rows(connection,"SELECT generation,aggregate_version FROM fee_event_notification_cursor WHERE tenant_id=? AND producer=? AND aggregate_id=? FOR UPDATE",tenant,event.producer(),event.aggregateId()).getFirst();
        long knownGeneration=((Number)stream.get("generation")).longValue();
        var knownVersion=new java.math.BigInteger(stream.get("aggregate_version").toString());
        if(generation<knownGeneration)return suppressFeeEvent(connection,event,"STALE_GENERATION");
        if(generation==knownGeneration&&version.compareTo(knownVersion)<0)return suppressFeeEvent(connection,event,"OUT_OF_ORDER_VERSION");
        execute(connection,"UPDATE fee_event_notification_cursor SET generation=?,aggregate_version=? WHERE tenant_id=? AND producer=? AND aggregate_id=?",generation,version,tenant,event.producer(),event.aggregateId());
        return true;
    }
    private static boolean suppressFeeEvent(Connection connection,DomainEvent event,String reason)throws SQLException{
        execute(connection,"INSERT INTO fee_notification_suppression VALUES(?,?,?,?,?,?,?)",event.eventId(),Long.parseLong(event.tenantId()),object(event),reason,generation(event),new java.math.BigInteger(event.aggregateVersion()),Timestamp.from(now(connection)));
        return false;
    }
    private static Map<String,Object> readResult(String op,String message,Object at){return Map.of("operationId",op,"messageId",message,"state","READ","readAt",utc(at));}
    static Calendar calendar(){return Calendar.getInstance(TimeZone.getTimeZone("UTC"));}
    private static String utc(Object value){return value instanceof Timestamp t?t.toInstant().toString():value instanceof LocalDateTime t?t.toInstant(ZoneOffset.UTC).toString():Instant.parse(value.toString()).toString();}
    private static String text(Map<String,Object> row,String key){Object value=row.get(key);if(value instanceof Clob clob)try{return clob.getSubString(1,Math.toIntExact(clob.length()));}catch(SQLException e){throw new Problem(503,"MESSAGE_FACT_INVALID");}return Objects.toString(value,"");}
    private static Instant now(Connection c)throws SQLException{try(var q=c.createStatement();var r=q.executeQuery("SELECT CURRENT_TIMESTAMP(3)")){r.next();return r.getTimestamp(1,calendar()).toInstant();}}
    private static PreparedStatement prepare(Connection c,String sql,Object...args)throws SQLException{var q=c.prepareStatement(sql);for(int i=0;i<args.length;i++)if(args[i]instanceof Timestamp t)q.setTimestamp(i+1,t,calendar());else if(args[i] instanceof UUID id)q.setString(i+1,id.toString());else q.setObject(i+1,args[i]);return q;}
    private static int execute(Connection c,String sql,Object...args)throws SQLException{try(var q=prepare(c,sql,args)){return q.executeUpdate();}}
    private static List<Map<String,Object>> rows(Connection c,String sql,Object...args)throws SQLException{try(var q=prepare(c,sql,args);var r=q.executeQuery()){var out=new ArrayList<Map<String,Object>>();while(r.next()){var row=new LinkedHashMap<String,Object>();var meta=r.getMetaData();for(int i=1;i<=meta.getColumnCount();i++){String label=meta.getColumnLabel(i).toLowerCase(Locale.ROOT);Object value=meta.getColumnType(i)==Types.TIMESTAMP?r.getTimestamp(i,calendar()):r.getObject(i);row.put(label,value);}out.add(row);}return out;}}
}
