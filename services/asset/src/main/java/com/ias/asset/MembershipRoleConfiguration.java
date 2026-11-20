package com.ias.asset;
import org.springframework.context.annotation.*;import org.springframework.boot.web.servlet.FilterRegistrationBean;
@Configuration
public class MembershipRoleConfiguration {
 @Bean FilterRegistrationBean<MembershipRoleRequestBoundary> membershipRoleBody(){var bean=new FilterRegistrationBean<>(new MembershipRoleRequestBoundary());bean.setOrder(-190);return bean;}
}
