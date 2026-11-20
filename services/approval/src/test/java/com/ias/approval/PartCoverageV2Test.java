package com.ias.approval;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PartCoverageV2Test {
    private static String id(int n){return String.format("00000000-0000-0000-0000-%012d",n);}
    private static Map<String,Object> line(){
        var binding=new LinkedHashMap<String,Object>();binding.put("approvalId",id(7));binding.put("approvalVersion","1");binding.put("warehouseId","2");binding.put("partId","3");binding.put("quantity","4");binding.put("unitCode","EA");binding.put("allocationRule","FULL_LINE_SINGLE_USE_V1");
        var line=new LinkedHashMap<String,Object>();line.put("id",id(2));line.put("ticketId",id(3));line.put("roundId",id(4));line.put("amountCents","1000");line.put("kind","PART");line.put("partBinding",binding);return line;
    }
    @Test void financeGoldenCoverageAndTamper(){
        var original=FeeApprovalAuthority.lines(Map.of("coverageVersion","2","lines",List.of(line())));
        assertEquals("fcdc9f05641cf71178c8bf3eeec685b8804c59a083d592e68c041e183f925f04",FeeApprovalPersistence.sha(FeeApprovalPersistence.json(original)));
        var changed=line();((Map<String,Object>)changed.get("partBinding")).put("partId","4");
        assertNotEquals(FeeApprovalPersistence.sha(FeeApprovalPersistence.json(original)),FeeApprovalPersistence.sha(FeeApprovalPersistence.json(FeeApprovalAuthority.lines(Map.of("coverageVersion","2","lines",List.of(changed))))));
    }
    @Test void invalidAndDuplicateBindingsFailClosed(){
        var bad=line();((Map<String,Object>)bad.get("partBinding")).put("quantity","01");assertThrows(ApprovalFailure.class,()->FeeApprovalAuthority.lines(Map.of("coverageVersion","2","lines",List.of(bad))));
        var a=line();var b=line();b.put("id",id(8));assertThrows(ApprovalFailure.class,()->FeeApprovalAuthority.lines(Map.of("coverageVersion","2","lines",List.of(a,b))));
        var unbound=line();unbound.remove("partBinding");assertThrows(ApprovalFailure.class,()->FeeApprovalAuthority.lines(Map.of("coverageVersion","2","lines",List.of(unbound))));
        var chinese=line();((Map<String,Object>)chinese.get("partBinding")).put("unitCode","件");assertEquals("件",((Map<?,?>)FeeApprovalAuthority.lines(Map.of("coverageVersion","2","lines",List.of(chinese))).getFirst().get("partBinding")).get("unitCode"));
        var padded=line();((Map<String,Object>)padded.get("partBinding")).put("unitCode"," 件 ");assertThrows(ApprovalFailure.class,()->FeeApprovalAuthority.lines(Map.of("coverageVersion","2","lines",List.of(padded))));
    }
    @Test void legacyRemainsFiveFields(){var old=line();old.remove("partBinding");assertEquals(5,FeeApprovalAuthority.lines(Map.of("lines",List.of(old))).getFirst().size());}
}
