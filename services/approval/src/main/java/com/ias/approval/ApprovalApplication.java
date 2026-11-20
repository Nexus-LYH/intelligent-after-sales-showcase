package com.ias.approval;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import javax.sql.DataSource;
import java.time.Clock;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

@SpringBootApplication
public class ApprovalApplication {
    public static void main(String[] args) { SpringApplication.run(ApprovalApplication.class, args); }
    @Bean @ConditionalOnMissingBean(ApprovalAccess.class)
    ApprovalAccess approvalAccess() { return com.ias.trust.Chain.active()?new TrustedApprovalAccess():new ApprovalAccess.Unavailable(); }
    @Bean org.springframework.boot.web.servlet.FilterRegistrationBean<com.ias.trust.EdgeFilter> approvalEdges() {
        var filter=new org.springframework.boot.web.servlet.FilterRegistrationBean<>(new com.ias.trust.EdgeFilter("approval"));filter.setOrder(-200);return filter;
    }
    @Bean Clock approvalClock() { return Clock.systemUTC(); }
    @Bean JsonMapperBuilderCustomizer approvalJson() {
        return builder -> builder.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .withCoercionConfig(LogicalType.Textual, coercion -> coercion
                        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
    }
    @Bean ApprovalStore approvalStore(DataSource source) { return new JdbcApprovalStore(source); }
    @Bean ApprovalQueries approvalQueries(DataSource source){return new ApprovalQueries(source);}
    @Bean ApprovalService approvalService(ApprovalStore store, ApprovalAccess access, Clock clock) {
        return new ApprovalService(store, access, clock);
    }
}
