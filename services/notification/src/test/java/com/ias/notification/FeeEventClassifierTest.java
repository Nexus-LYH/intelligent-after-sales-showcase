package com.ias.notification;

import com.ias.trust.Problem;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FeeEventClassifierTest {
    private DomainEvent part(String producer,String type,Map<String,String> changes){
        UUID operation=UUID.randomUUID();String receipt=UUID.randomUUID().toString();var payload=new HashMap<>(Map.ofEntries(Map.entry("ticketId",UUID.randomUUID().toString()),Map.entry("roundId",UUID.randomUUID().toString()),Map.entry("executionId",receipt),Map.entry("feeGroupId",UUID.randomUUID().toString()),Map.entry("generation","4"),Map.entry("lineId",UUID.randomUUID().toString()),Map.entry("warehouseId","11"),Map.entry("partId","101"),Map.entry("quantity","2"),Map.entry("amountCents","100"),Map.entry("companyId","2")));payload.putAll(changes);
        return new DomainEvent(operation,type,1,"1",receipt,"4",Instant.parse("2026-10-27T06:00:00.000Z"),producer,operation,payload);
    }
    @Test void partAcceptsOnlyFrozenStockEnvelope(){assertEquals(FeeEventClassifier.Kind.PART,FeeEventClassifier.validate(part("stock","inventory.consumed",Map.of())));}
    @Test void ticketCannotMasqueradeAsPart(){UUID id=UUID.randomUUID();String execution=UUID.randomUUID().toString();var event=new DomainEvent(id,"ticket.fee_execution_recorded",1,"1",execution,"1",Instant.parse("2026-10-27T06:00:00.000Z"),"ticket",id,Map.ofEntries(Map.entry("ticketId",UUID.randomUUID().toString()),Map.entry("roundId",UUID.randomUUID().toString()),Map.entry("executionId",execution),Map.entry("feeGroupId",UUID.randomUUID().toString()),Map.entry("generation","1"),Map.entry("completionId",UUID.randomUUID().toString()),Map.entry("lineId",UUID.randomUUID().toString()),Map.entry("processingRecordId",UUID.randomUUID().toString()),Map.entry("amountCents","1"),Map.entry("companyId","2"),Map.entry("assignmentId",UUID.randomUUID().toString()),Map.entry("kind","PART")));assertEquals("INVALID_FEE_EVENT",assertThrows(Problem.class,()->FeeEventClassifier.validate(event)).code);}
    @Test void partRejectsMissingExtraOrMismatchedImmutableFields(){
        var original=part("stock","inventory.consumed",Map.of());var missing=new HashMap<>(original.payload());missing.remove("lineId");var incomplete=new DomainEvent(original.eventId(),original.type(),1,original.tenantId(),original.aggregateId(),original.aggregateVersion(),original.occurredAt(),original.producer(),original.correlationId(),missing);assertEquals("INVALID_FEE_EVENT",assertThrows(Problem.class,()->FeeEventClassifier.validate(incomplete)).code);
        assertEquals("INVALID_FEE_EVENT",assertThrows(Problem.class,()->FeeEventClassifier.validate(part("stock","inventory.consumed",Map.of("warehouseId",UUID.randomUUID().toString())))).code);
        var e=part("stock","inventory.consumed",Map.of("generation","5"));assertEquals("INVALID_FEE_EVENT",assertThrows(Problem.class,()->FeeEventClassifier.validate(e)).code);
    }
}
