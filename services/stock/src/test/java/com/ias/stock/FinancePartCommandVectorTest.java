package com.ias.stock;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class FinancePartCommandVectorTest {
 @Test void canonicalDigestMatchesTicketGoldenVector(){var b=Map.<String,Object>ofEntries(Map.entry("operationId","00000000-0000-0000-0000-000000000001"),Map.entry("groupId","00000000-0000-0000-0000-000000000002"),Map.entry("completionId","00000000-0000-0000-0000-000000000003"),Map.entry("quoteId","00000000-0000-0000-0000-000000000004"),Map.entry("quoteVersion","2"),Map.entry("generation","3"),Map.entry("lineId","00000000-0000-0000-0000-000000000005"),Map.entry("orderId","00000000-0000-0000-0000-000000000006"),Map.entry("useOperationId","00000000-0000-0000-0000-000000000007"),Map.entry("partId","101"),Map.entry("quantity","2"),Map.entry("amountCents","1200"),Map.entry("warehouseId","11"),Map.entry("companyId","21"));assertEquals("fb75bceff36a5327a26d1fdfd8dee8cd166d3b767dd4de20e6dcfeadc9ace0f0",new FinancePartExecution.Command(b).digest());}
}
