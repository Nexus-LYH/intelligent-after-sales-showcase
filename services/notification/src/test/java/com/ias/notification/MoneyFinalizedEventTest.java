package com.ias.notification;

import com.ias.trust.Problem;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MoneyFinalizedEventTest {
    static Map<String,Object> payload(String action,long version,long amount,String original){var p=new LinkedHashMap<String,Object>();p.put("schemaVersion","1");p.put("operationId",UUID.nameUUIDFromBytes((action+version).getBytes()).toString());p.put("tenantId","1");p.put("companyId","2");p.put("groupId",UUID.nameUUIDFromBytes("group".getBytes()).toString());p.put("billId",UUID.nameUUIDFromBytes("bill".getBytes()).toString());p.put("completionId",UUID.nameUUIDFromBytes("completion".getBytes()).toString());p.put("accountVersion",Long.toString(version));p.put("action",action);p.put("businessId",UUID.nameUUIDFromBytes(("business"+version).getBytes()).toString());p.put("originalPaymentId",original);p.put("amountCents",Long.toString(amount));p.put("authorityDigest","a".repeat(64));p.put("executionReceiptsDigest","b".repeat(64));p.put("occurredAt","2026-10-26T07:46:01.123456Z");return p;}
    @Test void frozenActionsMapToStableCanonicalEvents(){
        for(String action:List.of("COLLECTION_RECORDED","COLLECTION_REVERSED","COLLECTION_REBOOKED")){var e=new MoneyFinalizedEvent(MoneyFinalizedEvent.COLLECTION,payload(action,1,action.equals("COLLECTION_REVERSED")?0:100,null)).domainEvent();assertEquals("finance.collection_finalized",e.type());assertEquals(e.eventId().toString(),e.correlationId().toString());assertEquals("2026-10-26T07:46:01.123456Z",e.payload().get("moneyOccurredAt"));}
        assertEquals("finance.refund_finalized",new MoneyFinalizedEvent(MoneyFinalizedEvent.REFUND,payload("REFUND_VOUCHER_RECORDED",2,50,UUID.randomUUID().toString())).domainEvent().type());
    }
    @Test void exactFieldsTypesActionsAndDigestsFailClosed(){
        var extra=payload("COLLECTION_RECORDED",1,100,null);extra.put("recipientUserIds",List.of("9"));assertEquals(400,assertThrows(Problem.class,()->new MoneyFinalizedEvent(MoneyFinalizedEvent.COLLECTION,extra)).status);
        var mismatch=payload("REFUND_VOUCHER_RECORDED",1,10,null);assertThrows(Problem.class,()->new MoneyFinalizedEvent(MoneyFinalizedEvent.COLLECTION,mismatch));
        for(String key:List.of("authorityDigest","executionReceiptsDigest")){var bad=payload("COLLECTION_RECORDED",1,10,null);bad.put(key,"A".repeat(64));assertThrows(Problem.class,()->new MoneyFinalizedEvent(MoneyFinalizedEvent.COLLECTION,bad));}
    }
}
