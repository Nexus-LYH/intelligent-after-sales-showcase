package com.ias.identity.auth;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class FeeMoneyPolicyTest {
 @Test void financialApprovalDoesNotRequireOrGrantCashHandling(){var caps=Set.of("approval.fees.read","approval.fees.finance");assertTrue(DomainPolicy.allowed("EMPLOYEE",caps,"FEE_FINANCE"));assertFalse(DomainPolicy.allowed("EMPLOYEE",caps,"MONEY_WRITE"));assertFalse(DomainPolicy.allowed("CUSTOMER",caps,"FEE_FINANCE"));assertFalse(DomainPolicy.allowed("EMPLOYEE",Set.of("approval.fees.finance"),"FEE_FINANCE"));}
 @Test void recoveryKeepsReadQualificationButRequiresCsrf(){assertTrue(DomainPolicy.allowed("EMPLOYEE",Set.of("approval.fees.read"),"FEE_RECOVER"));assertTrue(DomainPolicy.write("FEE_RECOVER"));assertFalse(DomainPolicy.allowed("EMPLOYEE",Set.of("approval.fees.read"),"FEE_SUBMIT"));assertFalse(DomainPolicy.allowed("CUSTOMER",DomainPolicy.CAPABILITIES,"FEE_RECOVER"));}
 @Test void moneyNeverBorrowsQuoteOrFeeAuthority(){assertFalse(DomainPolicy.allowed("EMPLOYEE",Set.of("finance.quote.read","finance.quote.write","approval.fees.read","approval.fees.finance"),"MONEY_READ"));assertTrue(DomainPolicy.allowed("EMPLOYEE",Set.of("finance.money.read"),"MONEY_READ"));assertFalse(DomainPolicy.allowed("EMPLOYEE",Set.of("finance.money.read"),"MONEY_WRITE"));assertFalse(DomainPolicy.allowed("EMPLOYEE",Set.of("finance.money.collect"),"MONEY_WRITE"));assertTrue(DomainPolicy.allowed("EMPLOYEE",Set.of("finance.money.read","finance.money.collect"),"MONEY_WRITE"));assertFalse(DomainPolicy.allowed("CUSTOMER",DomainPolicy.CAPABILITIES,"MONEY_WRITE"));assertTrue(DomainPolicy.write("MONEY_WRITE"));}
}
