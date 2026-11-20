package com.ias.ticket;
import com.ias.trust.Problem;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class FeePartCommandTest {
 static Map<String,Object> vector(){return new HashMap<>(Map.ofEntries(Map.entry("operationId","00000000-0000-0000-0000-000000000001"),Map.entry("groupId","00000000-0000-0000-0000-000000000002"),Map.entry("completionId","00000000-0000-0000-0000-000000000003"),Map.entry("quoteId","00000000-0000-0000-0000-000000000004"),Map.entry("quoteVersion","2"),Map.entry("generation","3"),Map.entry("lineId","00000000-0000-0000-0000-000000000005"),Map.entry("orderId","00000000-0000-0000-0000-000000000006"),Map.entry("useOperationId","00000000-0000-0000-0000-000000000007"),Map.entry("partId","101"),Map.entry("quantity","2"),Map.entry("amountCents","1200"),Map.entry("warehouseId","11"),Map.entry("companyId","21")));}
 @Test void canonicalDigestMatchesStockGoldenVector(){assertEquals("fb75bceff36a5327a26d1fdfd8dee8cd166d3b767dd4de20e6dcfeadc9ace0f0",new FeePartCommand(vector()).digest());var changed=vector();changed.put("quantity","3");assertNotEquals(new FeePartCommand(vector()).digest(),new FeePartCommand(changed).digest());}
 @Test void noncanonicalOrExtraFieldsAreRejected(){for(Object value:List.of("02","0","9223372036854775808",2)){var input=vector();input.put("quantity",value);assertThrows(Problem.class,()->new FeePartCommand(input));}var input=vector();input.put("extra","x");assertThrows(Problem.class,()->new FeePartCommand(input));}
}
