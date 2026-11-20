package com.ias.finance;
import com.ias.trust.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.context.annotation.*;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class FinanceApplication {
 public static void main(String[] args){if(!Chain.active()||!"finance".equals(Chain.role()))throw new IllegalStateException("Finance trusted runtime required");SpringApplication.run(FinanceApplication.class,args);}
 @Bean FinanceAccess access(javax.sql.DataSource source){return new TrustedFinanceAccess(source);}
 static String databaseName(String restorePrefix,String configured){if(restorePrefix==null||restorePrefix.isBlank()){String name=configured==null?"ias_finance_test":configured;if(!name.matches("ias_finance_[a-z0-9_]+"))throw new IllegalStateException("Dedicated finance schema required");return name;}if(!restorePrefix.matches("ias_restore_[a-z0-9_]{1,30}"))throw new IllegalStateException("Invalid restore prefix");return restorePrefix+"_finance";}
 @Bean org.springframework.beans.factory.InitializingBean databaseBoundary(javax.sql.DataSource source){return ()->{String schema=databaseName(System.getenv("IAS_RESTORE_PREFIX"),System.getenv("IAS_FINANCE_SCHEMA"));try(var c=source.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT @@port,DATABASE(),@@datadir")){r.next();if(r.getInt(1)!=3307||!schema.equals(r.getString(2))||!"C:/ias-local/Develop/IAS-MySQL84/data/".equals(r.getString(3).replace('\\','/')))throw new IllegalStateException("Dedicated3307 finance boundary failed");}try(var c=source.getConnection();var s=c.createStatement()){s.executeQuery("SELECT application_id FROM finance_customer_confirmation WHERE 1=0").close();s.executeQuery("SELECT application_id FROM finance_approval_request WHERE 1=0").close();for(String table:java.util.List.of("finance_quote_decision","finance_money_account","finance_money_entry","finance_money_reference","finance_quote_source","finance_quote_source_event","finance_quote_source_outbox","finance_manual_money_fact","finance_approval_completion_mirror","finance_receivable_execution_fact","finance_money_terminal_fact","finance_money_notification_delivery"))s.executeQuery("SELECT tenant_id FROM "+table+" WHERE 1=0").close();}};}
 @Bean FilterRegistrationBean<EdgeFilter> edges(){var f=new FilterRegistrationBean<>(new EdgeFilter("finance"));f.setOrder(-200);return f;}
 @Bean FilterRegistrationBean<FinanceRequestBoundary> requestBoundary(){var f=new FilterRegistrationBean<>(new FinanceRequestBoundary());f.setOrder(-150);return f;}
}
