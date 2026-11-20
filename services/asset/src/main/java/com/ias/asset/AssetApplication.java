package com.ias.asset;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.context.annotation.*;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import com.ias.trust.*;
@SpringBootApplication
public class AssetApplication {
 static String databaseName(String prefix){if(prefix==null||prefix.isBlank())return "ias_asset_test";if(!prefix.matches("ias_restore_[a-z0-9_]{1,30}"))throw new IllegalStateException("Invalid restore prefix");return prefix+"_asset";}
 @Bean org.springframework.beans.factory.InitializingBean databaseBoundary(javax.sql.DataSource dataSource){return ()->{String expected=databaseName(System.getenv("IAS_RESTORE_PREFIX"));try(var c=dataSource.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT @@port,DATABASE(),@@datadir")){r.next();if(r.getInt(1)!=3307||!r.getString(2).equals(expected)||!r.getString(3).replace('\\','/').equalsIgnoreCase("C:/ias-local/Develop/IAS-MySQL84/data/"))throw new IllegalStateException("Dedicated asset database required");}try(var c=dataSource.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM flyway_schema_history WHERE version='1' AND success=1")){r.next();if(r.getInt(1)!=1)throw new IllegalStateException("Explicit asset migration required");}};}
 public static void main(String[]args){if(!Chain.active()||!Chain.role().equals("asset"))throw new IllegalStateException("Asset TLS configuration required");SpringApplication.run(AssetApplication.class,args);}
 @Bean FilterRegistrationBean<EdgeFilter> edges(){var bean=new FilterRegistrationBean<>(new EdgeFilter("asset"));bean.setOrder(-200);return bean;}
 @Bean TransactionTemplate transactions(PlatformTransactionManager manager){var tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(5);return tx;}
}
