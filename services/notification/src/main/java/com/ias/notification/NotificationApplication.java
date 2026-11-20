package com.ias.notification;
import com.ias.trust.*;
import com.mysql.cj.jdbc.MysqlDataSource;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import javax.sql.DataSource;

@SpringBootApplication
public class NotificationApplication {
    public static void main(String[] args){if(!Chain.active()||!"notification".equals(Chain.role()))throw new IllegalStateException("Notification private TLS configuration required");SpringApplication.run(NotificationApplication.class,args);}
    @Bean FilterRegistrationBean<EdgeFilter> notificationEdges(){var bean=new FilterRegistrationBean<>(new EdgeFilter("notification"));bean.setOrder(-200);return bean;}
    @Bean FilterRegistrationBean<NotificationNoStore> notificationNoStore(){var bean=new FilterRegistrationBean<>(new NotificationNoStore());bean.setOrder(-100);return bean;}
    @Bean @ConditionalOnMissingBean(DataSource.class) DataSource notificationDataSource(Environment environment){var source=new MysqlDataSource();source.setURL(required(environment,"spring.datasource.url"));source.setUser(required(environment,"spring.datasource.username"));source.setPassword(required(environment,"spring.datasource.password"));return source;}
    @Bean @ConditionalOnMissingBean(NotificationAccess.class) NotificationAccess notificationAccess(){return Chain.active()?new TrustedNotificationAccess():new NotificationAccess.Unavailable();}
    @Bean MessageInbox messageInbox(DataSource source,NotificationAccess access){return new MessageInbox(source,access);}
    @Bean org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer notificationJson(){return builder->builder.enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION);}
    private static String required(Environment env,String key){String value=env.getProperty(key);if(value==null||value.isBlank())throw new IllegalStateException("Notification private datasource configuration required");return value;}
}
