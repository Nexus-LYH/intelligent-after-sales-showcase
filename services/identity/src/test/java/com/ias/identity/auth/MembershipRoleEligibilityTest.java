package com.ias.identity.auth;
import org.junit.jupiter.api.*;import java.util.*;import static org.junit.jupiter.api.Assertions.*;
class MembershipRoleEligibilityTest {
 StockEngineerQualificationTest x;
 @BeforeEach void prepare(){x=new StockEngineerQualificationTest();x.prepare();x.db.update("INSERT INTO tenant VALUES(8,1)");x.db.update("INSERT INTO identity_user VALUES(41,7,NULL,'CUSTOMER',1,0),(61,8,NULL,'CUSTOMER',1,0)");}
 Map<String,Object> facts(){return MembershipRoleEligibilityController.facts(x.db,7,new TreeSet<>(List.of(41L,42L,61L)));}
 @Test void customerEligibilityIsBoundToTenantAndDoesNotBorrowEmployeeOrOtherTenant(){var f=facts();assertEquals("7",f.get("tenantId"));assertEquals(List.of("41","42","61"),f.get("userIds"));assertEquals(List.of("41"),f.get("eligibleUserIds"));assertNotNull(f.get("checkedAt"));}
 @Test void currentUserOrTenantDisableOrRestrictedPasswordImmediatelyRemovesEligibility(){x.db.update("UPDATE identity_user SET status=0 WHERE id=41");assertEquals(List.of(),facts().get("eligibleUserIds"));x.db.update("UPDATE identity_user SET status=1,must_change_password=1 WHERE id=41");assertEquals(List.of(),facts().get("eligibleUserIds"));x.db.update("UPDATE identity_user SET must_change_password=0 WHERE id=41");x.db.update("UPDATE tenant SET status=0 WHERE id=7");assertEquals(List.of(),facts().get("eligibleUserIds"));}
}
