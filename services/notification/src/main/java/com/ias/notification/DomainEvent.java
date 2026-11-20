package com.ias.notification;

import tools.jackson.databind.json.JsonMapper;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Reference-only candidate envelope from events-v1.yaml. No user authority is conveyed by an event. */
public record DomainEvent(UUID eventId, String type, int schemaVersion, String tenantId,
                          String aggregateId, String aggregateVersion, Instant occurredAt,
                          String producer, UUID correlationId, Map<String,String> payload,
                          UUID traceId,UUID causationId) {
    public DomainEvent(UUID eventId,String type,int schemaVersion,String tenantId,String aggregateId,String aggregateVersion,Instant occurredAt,String producer,UUID correlationId,Map<String,String> payload){this(eventId,type,schemaVersion,tenantId,aggregateId,aggregateVersion,occurredAt,producer,correlationId,payload,null,null);}
    private static final JsonMapper JSON=JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private static final Set<String> PAYLOAD=Set.of("ticketId","roundId","requestId","decisionId","executionId","reservationId","quoteId","quoteVersion","feeGroupId","generation","barrierId","ownerId","stateCode","fileId","knowledgeVersionId","runId","jobId","securityEpoch","completionId","applicationId","lineId","processingRecordId","amountCents","companyId","assignmentId","kind","groupId","billId","accountVersion","action","businessId","originalPaymentId","authorityDigest","executionReceiptsDigest","moneyOccurredAt","warehouseId","partId","quantity","solutionId");
    private static final Map<String,Set<String>> TYPES=Map.of(
        "ticket",Set.of("submitted","accepted","assigned","transferred","supplement_requested","supplement_responded","resolution_submitted","confirmed","closed","cancel_requested","cancel_started","cancel_completed","fee_execution_recorded"),
        "approval",Set.of("submitted","approved","rejected","withdrawn","terminated","fee_completed"),
        "stock",Set.of("reserved","reservation_failed","expired","released","dispatched","returned","consumed"),
        "finance",Set.of("quote_confirmed","cost_confirmed","settlement_recorded","reversed","collection_finalized","refund_finalized"),
        "ai",Set.of("knowledge.version_published","knowledge.archived","ai.run_finished","ai.run_failed"),
        "notification",Set.of("job.failed"),"identity",Set.of("user_disabled","permissions_changed","tenant_disabled"));
    public DomainEvent {
        Objects.requireNonNull(eventId); Objects.requireNonNull(correlationId); Objects.requireNonNull(occurredAt);
        if(schemaVersion!=1 || !validType(producer,type)) throw invalid();
        if(tenantId==null || !tenantId.matches("[1-9][0-9]{0,18}") || new BigInteger(tenantId).compareTo(BigInteger.valueOf(Long.MAX_VALUE))>0) throw invalid();
        if(aggregateVersion==null || !aggregateVersion.matches("0|[1-9][0-9]{0,19}")) throw invalid();
        if(aggregateId==null || !aggregateId.matches("[A-Za-z0-9_-]{1,100}") || occurredAt.getNano()%1_000_000!=0 || !occurredAt.toString().matches("[0-9]{4}-.*")) throw invalid();
        if(payload==null || !PAYLOAD.containsAll(payload.keySet())) throw invalid();
        for(var entry:payload.entrySet()) if(entry.getValue()==null || !entry.getValue().matches(entry.getKey().equals("moneyOccurredAt")?"[0-9TZ:.-]{20,40}":Set.of("stateCode","kind","action").contains(entry.getKey())?"[A-Z][A-Z0-9_]{0,63}":"[A-Za-z0-9_-]{1,100}")) throw invalid();
        payload=Collections.unmodifiableMap(new TreeMap<>(payload));
    }
    private static boolean validType(String producer,String type) {
        if(producer==null || type==null || !TYPES.containsKey(producer)) return false;
        if(producer.equals("ai") || producer.equals("notification")) return TYPES.get(producer).contains(type);
        String namespace=producer.equals("stock")?"inventory":producer;
        return type.startsWith(namespace+".") && TYPES.get(producer).contains(type.substring(namespace.length()+1));
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("INVALID_EVENT_ENVELOPE");}
    public boolean allowsConsumer(String id){
        if(id.equals("notification")||id.equals("reporting"))return true;
        return switch(producer){
            case "ticket" -> (type.equals("ticket.cancel_requested")||type.equals("ticket.cancel_started")) && Set.of("approval","stock","finance").contains(id);
            case "approval" -> Set.of("stock","finance","ticket").contains(id);
            case "stock" -> Set.of("approval","finance","ticket").contains(id);
            case "finance" -> Set.of("approval","stock","ticket").contains(id);
            case "identity" -> Set.of("ticket","approval","stock","finance","ai").contains(id);
            default -> false;
        };
    }
    public String encode(){
        var out=new TreeMap<String,Object>(); out.put("eventId",eventId.toString());out.put("type",type);out.put("schemaVersion",schemaVersion);
        out.put("tenantId",tenantId);out.put("aggregateId",aggregateId);out.put("aggregateVersion",aggregateVersion);
        out.put("occurredAt",String.format(Locale.ROOT,"%s.%03dZ",occurredAt.toString().substring(0,19),occurredAt.getNano()/1_000_000));
        out.put("producer",producer);out.put("correlationId",correlationId.toString());out.put("payload",payload);
        if(traceId!=null)out.put("traceId",traceId.toString());if(causationId!=null)out.put("causationId",causationId.toString());
        String encoded=JSON.writeValueAsString(out); if(encoded.getBytes(StandardCharsets.UTF_8).length>65536) throw invalid(); return encoded;
    }
    public String sha(){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode().getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    @SuppressWarnings("unchecked") public static DomainEvent decode(String text){
        if(text==null || text.getBytes(StandardCharsets.UTF_8).length>65536) throw invalid();
        try {
            Map<String,Object> m=JSON.readValue(text,Map.class);
            Set<String> required=Set.of("eventId","type","schemaVersion","tenantId","aggregateId","aggregateVersion","occurredAt","producer","correlationId","payload");var allowed=new HashSet<>(required);allowed.addAll(Set.of("traceId","causationId"));if(!m.keySet().containsAll(required)||!allowed.containsAll(m.keySet()))throw invalid();
            if(!(m.get("schemaVersion") instanceof Integer n) || n!=1) throw invalid();
            if(!(m.get("occurredAt") instanceof String at)||!at.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z"))throw invalid();
            return new DomainEvent(uuid((String)m.get("eventId")),(String)m.get("type"),n,(String)m.get("tenantId"),(String)m.get("aggregateId"),(String)m.get("aggregateVersion"),Instant.parse(at),(String)m.get("producer"),uuid((String)m.get("correlationId")),(Map<String,String>)m.get("payload"),m.containsKey("traceId")?uuid((String)m.get("traceId")):null,m.containsKey("causationId")?uuid((String)m.get("causationId")):null);
        } catch(Exception e){throw invalid();}
    }
    private static UUID uuid(String text){UUID id=UUID.fromString(text);if(!id.toString().equals(text))throw invalid();return id;}
    @Override public String toString(){return "DomainEvent["+eventId+","+type+"]";}
}
