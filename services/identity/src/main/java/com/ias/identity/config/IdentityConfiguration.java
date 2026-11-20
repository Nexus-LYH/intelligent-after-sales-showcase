package com.ias.identity.config;

import com.ias.identity.auth.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.env.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.core.userdetails.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.*;

@Configuration
public class IdentityConfiguration {
    @Bean
    public org.springframework.boot.web.servlet.FilterRegistrationBean<com.ias.trust.EdgeFilter> trustedEdges() {
        var bean = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(new com.ias.trust.EdgeFilter("identity"));
        bean.setOrder(-200);
        return bean;
    }

    @Bean
    public org.springframework.beans.factory.InitializingBean confirmationProtocolSchema(javax.sql.DataSource dataSource) {
        return () -> {
            try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("SELECT COUNT(*) FROM flyway_schema_history WHERE version='2' AND success=1"); var result = statement.executeQuery()) {
                result.next();
                if (result.getInt(1) != 1)
                    throw new IllegalStateException("Confirmation protocol migration V2 is required before accepting requests");
            }
            try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("SELECT COUNT(*) FROM flyway_schema_history WHERE version='3' AND success=1"); var result = statement.executeQuery()) {
                result.next();
                if (result.getInt(1) != 1)
                    throw new IllegalStateException("Permission facts migration V3 is required before accepting requests");
            }
        };
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
    }

    @Bean
    public TransactionTemplate authTransactions(PlatformTransactionManager manager) {
        TransactionTemplate template = new TransactionTemplate(manager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.setTimeout(5);
        return template;
    }

    @Bean
    public UserDetailsService noDefaultUsers() {
        return username -> {
            throw new UsernameNotFoundException("Framework user login is not supported");
        };
    }

    @Bean
    public SecurityFilterChain security(HttpSecurity http, Environment environment,
                                        @Value("${server.address}") String address,
                                        @Value("${identity.auth.allowed-origins}") String allowed,
                                        @Value("${identity.auth.login-limit:30}") int loginLimit,
                                        @Value("${identity.auth.csrf-limit:100}") int csrfLimit) throws Exception {
        if (!environment.acceptsProfiles(Profiles.of("local")) || !(address.equals("127.0.0.1") || address.equals("::1"))) {
            throw new IllegalStateException("This implementation slice requires explicit local profile and loopback. Gateway/TLS integration is not implemented.");
        }
        Set<String> origins = new HashSet<>(Arrays.asList(allowed.split(",")));
        for (String origin : origins) {
            var uri = java.net.URI.create(origin);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || !("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost())))
                throw new IllegalStateException("Local origins must use loopback.");
        }
        http.formLogin(c -> c.disable()).httpBasic(c -> c.disable()).logout(c -> c.disable());
        http.sessionManagement(c -> c.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        http.securityContext(c -> c.securityContextRepository(new NullSecurityContextRepository()));
        http.requestCache(c -> c.disable());
        // Only these domain POST routes delegate CSRF to database-bound checks, under the same security chain.
        // Spring CSRF remains active for all other writes; there is no global csrf.disable() or HttpSession token store.
        http.csrf(c -> c.csrfTokenRepository(new DeniedRouteCsrfRepository()).ignoringRequestMatchers("/api/auth/login", "/api/auth/logout", "/api/auth/password", "/api/identity/users/*/sessions/revoke", "/api/identity/users/*/status", "/api/identity/users/*/scope", "/api/identity/employees", "/internal/auth/introspect", "/internal/identity/customer-enrollments/*", "/internal/identity/ticket-authorizations", "/internal/identity/ticket-candidates", "/internal/identity/domain-authorizations", "/internal/identity/approval-recipients", "/internal/identity/fee-recipients").ignoringRequestMatchers(org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher.withDefaults().matcher(org.springframework.http.HttpMethod.POST, "/internal/identity/stock-engineer-qualification")).ignoringRequestMatchers(org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher.withDefaults().matcher(org.springframework.http.HttpMethod.POST, "/internal/identity/customer-eligibility")));
        if (loginLimit < 1 || csrfLimit < 1 || loginLimit > 1000 || csrfLimit > 1000)
            throw new IllegalStateException("Invalid local rate limits");
        http.addFilterBefore(new BrowserGuardFilter(origins, loginLimit, csrfLimit), CsrfFilter.class);
        http.authorizeHttpRequests(c -> c
                .requestMatchers(org.springframework.http.HttpMethod.POST, "/internal/auth/introspect", "/internal/identity/customer-enrollments/*", "/internal/identity/ticket-authorizations", "/internal/identity/ticket-candidates", "/internal/identity/domain-authorizations", "/internal/identity/approval-recipients", "/internal/identity/fee-recipients", "/internal/identity/stock-engineer-qualification", "/internal/identity/customer-eligibility").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/auth/csrf", "/api/auth/me", "/api/identity/users", "/api/identity/users/*", "/api/identity/users/*/access", "/api/identity/users/*/status-operations/*", "/api/identity/users/*/scope-operations/*", "/api/identity/employee-create-operations/*").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/auth/login", "/api/auth/logout", "/api/auth/password").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/identity/users/*/sessions/revoke", "/api/identity/users/*/status", "/api/identity/users/*/scope", "/api/identity/employees").permitAll()
                .anyRequest().denyAll());
        http.exceptionHandling(c -> c
                .authenticationEntryPoint((request, response, e) -> AuthHttp.writeFailure(new AuthFailure(403, "ACCESS_DENIED", null), request, response))
                .accessDeniedHandler((request, response, e) -> AuthHttp.writeFailure(new AuthFailure(403, "ACCESS_DENIED", null), request, response)));
        return http.build();
    }
}





