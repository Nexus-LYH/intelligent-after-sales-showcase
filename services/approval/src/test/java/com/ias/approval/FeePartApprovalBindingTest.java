package com.ias.approval;
import com.ias.trust.Problem;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FeePartApprovalBindingTest {
 Map<String,Object> proof,binding,ticket;List<Map<String,Object>> lines;
 @BeforeEach void setup(){
  String id=UUID.randomUUID().toString(),t=UUID.randomUUID().toString(),r=UUID.randomUUID().toString(),a=UUID.randomUUID().toString();
  binding=new HashMap<>(Map.of("approvalId",id,"approvalVersion","2","warehouseId","3","partId","4","quantity","5"));ticket=Map.of("assignmentId",a,"engineerUserId","10");
  lines=List.of(Map.of("kind","PART","ticketId",t,"roundId",r,"partBinding",binding));
  proof=new HashMap<>(Map.of("tenantId","1","approvalId",id,"approvalVersion","2","warehouseId","3","ticketId",t,"roundId",r,"assignmentId",a,"applicantUserId","10","chargeMode","CUSTOMER_PAID","lines",List.of(Map.of("partId","4","quantity","5"))));
 }
 void verify(){FeePartApprovalBinding.verify((t,a,p,w)->proof,1,20,ticket,lines);}
 @Test void exactApprovedOwnerScopeMatches(){assertDoesNotThrow(this::verify);}
 @Test void catalogMustMatchTenantPartWarehouseUnitAndEnabledFlags(){
  binding.put("unitCode","pcs");var catalog=new HashMap<String,Object>(Map.of("tenantId","1","warehouseId","3","partId","4","unitCode","pcs","warehouseActive",true,"partActive",true));
  assertDoesNotThrow(()->FeePartApprovalBinding.catalog(1,binding,catalog));for(String key:List.of("tenantId","warehouseId","partId","unitCode","warehouseActive","partActive")){Object original=catalog.put(key,"wrong");assertThrows(Problem.class,()->FeePartApprovalBinding.catalog(1,binding,catalog));catalog.put(key,original);}
 }
 @Test void claimsMustMatchEveryOwnerFieldAndEveryPartBinding(){
  var claim=new HashMap<String,Object>(Map.of("tenantId","1","companyId","2","groupId","group","ticketId",lines.getFirst().get("ticketId"),"roundId",lines.getFirst().get("roundId"),"approvalId",binding.get("approvalId"),"binding",new HashMap<>(binding)));
  assertDoesNotThrow(()->FeePartApprovalBinding.claims(1,2,"group",lines,List.of(claim)));
  assertThrows(Problem.class,()->FeePartApprovalBinding.claims(1,2,"group",lines,List.of()));
  claim.put("groupId","other");assertThrows(Problem.class,()->FeePartApprovalBinding.claims(1,2,"group",lines,List.of(claim)));claim.put("groupId","group");
  var changed=new HashMap<>(binding);changed.put("quantity","6");claim.put("binding",changed);assertThrows(Problem.class,()->FeePartApprovalBinding.claims(1,2,"group",lines,List.of(claim)));
 }
 @Test void staleVersionWrongAssignmentAndWrongPayerCannotApprove(){
  for(String key:List.of("tenantId","approvalId","approvalVersion","warehouseId","ticketId","roundId","assignmentId","applicantUserId","chargeMode")){Object original=proof.put(key,"other");assertEquals("PART_APPROVAL_OWNER_BINDING_MISMATCH",assertThrows(Problem.class,this::verify).code);proof.put(key,original);}
 }
 @Test void partialQuantityOrDifferentPartCannotBorrowApprovedOrder(){proof.put("lines",List.of(Map.of("partId","4","quantity","4")));assertThrows(Problem.class,this::verify);proof.put("lines",List.of(Map.of("partId","6","quantity","5")));assertThrows(Problem.class,this::verify);}
}
