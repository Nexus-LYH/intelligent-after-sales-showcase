package com.ias.finance;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RestoreDatabaseNameTest {
 @Test void acceptsOnlyExplicitIsolatedPrefix(){assertEquals("ias_finance_test",FinanceApplication.databaseName(null,null));assertEquals("ias_restore_demo_finance",FinanceApplication.databaseName("ias_restore_demo",null));assertThrows(IllegalStateException.class,()->FinanceApplication.databaseName("ias_finance_test",null));}
}
