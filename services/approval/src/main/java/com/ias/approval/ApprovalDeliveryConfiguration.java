package com.ias.approval;

import com.ias.notification.*;
import com.ias.trust.Chain;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import javax.sql.DataSource;
import java.time.Duration;

@Configuration
@ConditionalOnProperty(name="ias.approval.delivery.enabled",havingValue="true")
public class ApprovalDeliveryConfiguration {
    @Bean(initMethod="start",destroyMethod="close") DeliveryWorker approvalDeliveryWorker(Environment env,ApprovalQueries queries){
        if(!Chain.active()||!"approval".equals(Chain.role()))throw new IllegalStateException("TRUSTED_APPROVAL_WORKER_REQUIRED");
        queries.requireDeliveryReady();
        DataSource physical;
        try{
            var type=Class.forName("com.mysql.cj.jdbc.MysqlDataSource");var instance=type.getConstructor().newInstance();
            type.getMethod("setURL",String.class).invoke(instance,env.getRequiredProperty("spring.datasource.url"));
            type.getMethod("setUser",String.class).invoke(instance,env.getRequiredProperty("spring.datasource.username"));
            type.getMethod("setPassword",String.class).invoke(instance,env.getRequiredProperty("spring.datasource.password"));
            type.getMethod("setConnectTimeout",int.class).invoke(instance,1000);type.getMethod("setSocketTimeout",int.class).invoke(instance,3000);
            physical=(DataSource)instance;
        }catch(Exception failure){throw new IllegalStateException("OWNER_PHYSICAL_DATASOURCE_REQUIRED");}
        return new DeliveryWorker(new JdbcDelivery(physical,10,Duration.ofSeconds(30)),new ApprovalDeliveryTransport(queries),10,Duration.ofMillis(500));
    }
}
