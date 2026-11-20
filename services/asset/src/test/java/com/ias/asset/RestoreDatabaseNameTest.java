package com.ias.asset;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RestoreDatabaseNameTest {
 @Test void acceptsOnlyExplicitIsolatedPrefix(){assertEquals("ias_asset_test",AssetApplication.databaseName(null));assertEquals("ias_restore_demo_asset",AssetApplication.databaseName("ias_restore_demo"));assertThrows(IllegalStateException.class,()->AssetApplication.databaseName("ias_asset_test"));}
}
