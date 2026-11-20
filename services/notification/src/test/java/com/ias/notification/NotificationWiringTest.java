package com.ias.notification;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.assertThat;

class NotificationWiringTest {
    @Test void bootWiresPhysicalMysqlAndFailClosedAccessWithoutOpeningDatabaseOrSocket(){
        new WebApplicationContextRunner().withUserConfiguration(NotificationApplication.class)
            .withPropertyValues("spring.datasource.url=jdbc:mysql://127.0.0.1:1/not-opened","spring.datasource.username=not-a-runtime-user","spring.datasource.password=not-a-runtime-secret","spring.sql.init.mode=never")
            .run(context->{assertThat(context).hasNotFailed().hasSingleBean(MessageInbox.class).hasSingleBean(NotificationController.class);
                assertThat(context.getBean(DataSource.class).getClass().getName()).isEqualTo("com.mysql.cj.jdbc.MysqlDataSource");
                assertThat(context.getBean(NotificationAccess.class)).isInstanceOf(NotificationAccess.Unavailable.class);
            });
    }
}
