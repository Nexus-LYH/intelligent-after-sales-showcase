package com.ias.notification;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DomainEventTest {
    DomainEvent create(String producer,String type,Map<String,String> payload){return new DomainEvent(UUID.randomUUID(),type,1,"1","ticket-1","0",Instant.parse("2026-10-21T01:00:00.000Z"),producer,UUID.randomUUID(),payload);}
    @Test void canonicalEnvelopeRoundTripsWithStableHash(){var event=create("ticket","ticket.closed",Map.of("ticketId","ticket-1"));assertEquals(event,DomainEvent.decode(event.encode()));assertEquals(event.sha(),DomainEvent.decode(event.encode()).sha());}
    @Test void stockOwnershipUsesInventoryNamespace(){assertDoesNotThrow(()->create("stock","inventory.reserved",Map.of()));assertThrows(IllegalArgumentException.class,()->create("ticket","inventory.reserved",Map.of()));}
    @Test void rejectSensitivePayloadAndProse(){assertThrows(IllegalArgumentException.class,()->create("ticket","ticket.closed",Map.of("password","secret")));assertThrows(IllegalArgumentException.class,()->create("ticket","ticket.closed",Map.of("ticketId","customer text or signed URL")));}
    @Test void rejectNoncanonicalIdsOrUnknownKeys(){var event=create("ticket","ticket.closed",Map.of());assertThrows(IllegalArgumentException.class,()->DomainEvent.decode(event.encode().replace("\"schemaVersion\":1","\"schemaVersion\":2")));assertThrows(IllegalArgumentException.class,()->DomainEvent.decode(event.encode().replaceFirst("\\{","{\"unexpected\":true,")));}
    @Test void returnedPayloadCannotMutateStoredHash(){var payload=new HashMap<String,String>();payload.put("ticketId","one");var e=create("ticket","ticket.closed",payload);String hash=e.sha();payload.put("ticketId","two");assertEquals(hash,e.sha());assertThrows(UnsupportedOperationException.class,()->e.payload().put("ticketId","two"));}
    @Test void optionalTraceAndCausationRemainImmutable(){var e=create("ticket","ticket.closed",Map.of());e=new DomainEvent(e.eventId(),e.type(),1,e.tenantId(),e.aggregateId(),e.aggregateVersion(),e.occurredAt(),e.producer(),e.correlationId(),e.payload(),UUID.randomUUID(),UUID.randomUUID());assertEquals(e,DomainEvent.decode(e.encode()));}
    @Test void duplicateFieldsAndNonMillisUtcAreRejected(){var e=create("ticket","ticket.closed",Map.of());assertThrows(IllegalArgumentException.class,()->DomainEvent.decode(e.encode().replaceFirst("\\{","{\"tenantId\":\"2\",")));assertThrows(IllegalArgumentException.class,()->DomainEvent.decode(e.encode().replace(".000Z","Z")));assertFalse(e.allowsConsumer("stock"));}
}
