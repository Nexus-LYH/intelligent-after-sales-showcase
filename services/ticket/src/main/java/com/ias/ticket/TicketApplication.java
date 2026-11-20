package com.ias.ticket;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.context.annotation.*;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import com.ias.trust.*;
@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication
public class TicketApplication {
 static String databaseName(String prefix){if(prefix==null||prefix.isBlank())return "ias_ticket_test";if(!prefix.matches("ias_restore_[a-z0-9_]{1,30}"))throw new IllegalStateException("Invalid restore prefix");return prefix+"_ticket";}
 @Bean org.springframework.beans.factory.InitializingBean databaseBoundary(javax.sql.DataSource dataSource){return ()->{String expected=databaseName(System.getenv("IAS_RESTORE_PREFIX"));try(var c=dataSource.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT @@port,DATABASE(),@@datadir")){r.next();if(r.getInt(1)!=3307||!r.getString(2).equals(expected)||!r.getString(3).replace('\\','/').equalsIgnoreCase("C:/ias-local/Develop/IAS-MySQL84/data/"))throw new IllegalStateException("Dedicated ticket database required");}try(var c=dataSource.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM flyway_schema_history WHERE version IN ('1','9','10','11','12') AND success=1")){r.next();if(r.getInt(1)!=5)throw new IllegalStateException("Explicit ticket migrations V1, V9, V10, V11 and V12 required");}};}
 public static void main(String[]args){if(!Chain.active()||!Chain.role().equals("ticket"))throw new IllegalStateException("Ticket TLS configuration required");SpringApplication.run(TicketApplication.class,args);}
 @Bean FilterRegistrationBean<EdgeFilter> edges(){var bean=new FilterRegistrationBean<>(new EdgeFilter("ticket"));bean.setOrder(-200);return bean;}
 @Bean TransactionTemplate transactions(PlatformTransactionManager manager){var tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout(5);return tx;}
}


