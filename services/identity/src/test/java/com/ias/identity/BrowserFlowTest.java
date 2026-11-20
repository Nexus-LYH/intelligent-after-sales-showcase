package com.ias.identity;

import com.ias.identity.auth.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real headless browser evidence. Fixture routes/transport faults exist only in test classes. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.DEFINED_PORT,properties={"server.port=18081","identity.auth.allowed-origins=http://127.0.0.1:18081,http://127.0.0.1:5173"})
@ContextConfiguration(initializers=AuthFlowTest.MigrateBeforeContext.class)
@Import(BrowserFlowTest.Support.class)
class BrowserFlowTest {
    static final String PASS="BROWSER_PASS: HttpOnly, native cookies, unread JSON recovery, omitted Cookie rejection, explicit new attempt";
    private static final Map<String,Object> RESULTS=new LinkedHashMap<>();
    static class Fixture {
        final Map<String,Object> credentials;
        Fixture(PasswordEncoder encoder) throws Exception {
            String suffix=UUID.randomUUID().toString().replace("-","").substring(0,12);
            String code="BR_"+suffix.toUpperCase(), account="browser."+suffix, password=new AuthCrypto().randomToken();
            TestDatabase.update("INSERT INTO tenant(code_normalized,name,status,created_at,updated_at) VALUES (?,'浏览器测试租户',1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",code);
            long tenant=TestDatabase.scalar("SELECT id FROM tenant WHERE code_normalized=?",code);
            TestDatabase.update("INSERT INTO identity_user(realm,tenant_id,account_normalized,identity_type,display_name,password_verifier,status,must_change_password,binding_state,created_at,updated_at) VALUES ('TENANT',?,?,'EMPLOYEE','浏览器测试用户',?,1,0,0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",tenant,account,encoder.encode(password));
            credentials=Map.of("realm","TENANT","tenantCode",code,"account",account,"password",password);
        }
    }
    @TestConfiguration static class Support {
        @Bean Fixture browserFixture(PasswordEncoder encoder) throws Exception { return new Fixture(encoder); }
        @Bean TestPage testPage(Fixture fixture) { return new TestPage(fixture); }
        @Bean @Order(0) SecurityFilterChain fixtureRoutes(HttpSecurity http) throws Exception {
            return http.securityMatcher("/__test/**").authorizeHttpRequests(c->c.requestMatchers(org.springframework.http.HttpMethod.GET,"/__test/**").permitAll().anyRequest().denyAll()).build();
        }
        @Bean FilterRegistrationBean<OncePerRequestFilter> cookieLossFixture() {
            var filter=new OncePerRequestFilter() {
                @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
                    if (request.getRequestURI().equals("/api/auth/login") && "omit".equals(request.getHeader("X-Fixture-Cookie"))) {
                        chain.doFilter(request,new HttpServletResponseWrapper(response) {
                            @Override public void addHeader(String name,String value) { if (!name.equalsIgnoreCase("Set-Cookie")) super.addHeader(name,value); }
                            @Override public void setHeader(String name,String value) { if (!name.equalsIgnoreCase("Set-Cookie")) super.setHeader(name,value); }
                        });
                    } else chain.doFilter(request,response);
                }
            };
            var registration=new FilterRegistrationBean<OncePerRequestFilter>(filter); registration.setOrder(-200); return registration;
        }
    }
    @RestController static class TestPage {
        final Fixture fixture;
        TestPage(Fixture fixture) { this.fixture=fixture; }
        @GetMapping("/__test/credentials") Map<String,Object> credentials() { return fixture.credentials; }
        @GetMapping(value="/__test/page",produces="text/html;charset=UTF-8") String page(@RequestParam(defaultValue="normal") String mode) {
            return """
                <!doctype html><html><head><meta charset="utf-8"></head><body><output id="browser-result" data-complete="false">RUNNING</output><script>
                (async()=>{
                  let step='start'; const check=(condition)=>{if(!condition)throw new Error(step)};
                  const result=document.getElementById('browser-result');
                  try {
                    __FAULT__
                    const payload=await (await fetch('/__test/credentials')).json();
                    step='preauth'; let pre=await (await fetch('/api/auth/csrf')).json(); check(pre.contextKind==='PREAUTH');
                    check(document.cookie==='');
                    const attempt=crypto.randomUUID();
                    step='login-cookie-json-unread'; let response=await fetch('/api/auth/login',{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-TOKEN':pre.token,'X-Login-Attempt-Id':attempt},body:JSON.stringify(payload)});
                    check(response.status===200); await response.body.cancel();
                    step='recover'; let identity=await (await fetch('/api/auth/me')).json(); check(identity.loginAttemptId===attempt && identity.account===payload.account && identity.tenant.code===payload.tenantCode);
                    check(document.cookie==='');
                    step='auth-csrf'; let auth=await (await fetch('/api/auth/csrf')).json(); check(auth.contextKind==='AUTH' && auth.loginAttemptId===attempt);
                    step='logout'; check((await fetch('/api/auth/logout',{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-TOKEN':auth.token}})).status===204);
                    check((await fetch('/api/auth/me')).status===401);
                    step='no-cookie-delivery'; pre=await (await fetch('/api/auth/csrf')).json();
                    const lost=crypto.randomUUID(); response=await fetch('/api/auth/login',{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-TOKEN':pre.token,'X-Login-Attempt-Id':lost,'X-Fixture-Cookie':'omit'},body:JSON.stringify(payload)});
                    check(response.status===200); await response.body.cancel(); check((await fetch('/api/auth/me')).status===401);
                    step='explicit-new-attempt'; pre=await (await fetch('/api/auth/csrf')).json(); const fresh=crypto.randomUUID();
                    response=await fetch('/api/auth/login',{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-TOKEN':pre.token,'X-Login-Attempt-Id':fresh},body:JSON.stringify(payload)});
                    check(response.status===200); identity=await (await fetch('/api/auth/me')).json(); check(identity.loginAttemptId===fresh && fresh!==lost);
                    result.textContent='BROWSER_PASS: HttpOnly, native cookies, unread JSON recovery, omitted Cookie rejection, explicit new attempt';
                    result.dataset.complete='true';
                  }catch(e){result.textContent='BROWSER_FAIL: '+step;result.dataset.complete='true';}
                })();
                </script></body></html>
                """.replace("__FAULT__",switch (mode) {
                    case "hang" -> "await new Promise(()=>{});";
                    case "fail" -> "step='forced-failure';throw new Error('fixture');";
                    default -> "";
                });
        }
    }
    @Test void realBrowserCookiesAndBothDeliveryRecoveryBranches() throws Exception {
        assertCompleted(runBrowser("normal"),0);
    }
    @Test void realBrowserHangingFlowMustNotBeAccepted() throws Exception {
        String dom=runBrowser("hang");
        assertThrows(AssertionError.class,()->assertCompleted(dom,0));
        assertEquals("RUNNING",org.jsoup.Jsoup.parse(dom).selectFirst("#browser-result").text());
    }
    @Test void realBrowserExplicitFailureMustNotBeAccepted() throws Exception {
        String dom=runBrowser("fail");
        assertThrows(AssertionError.class,()->assertCompleted(dom,0));
        assertEquals("BROWSER_FAIL: forced-failure",org.jsoup.Jsoup.parse(dom).selectFirst("#browser-result").text());
    }
    private String runBrowser(String mode) throws Exception {
        Path browser=Path.of("C:/Program Files/Google/Chrome/Application/chrome.exe");
        assertTrue(Files.isExecutable(browser),"Real browser executable is required; this is not a simulated browser test");
        Path profile=Files.createTempDirectory("ias-browser-test-");
        Process process=new ProcessBuilder(browser.toString(),"--headless=new","--disable-gpu","--no-first-run","--no-default-browser-check","--disable-extensions",
            "--user-data-dir="+profile,"--dump-dom","--virtual-time-budget=15000","http://127.0.0.1:18081/__test/page?mode="+mode)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            var output=CompletableFuture.supplyAsync(()-> {
                try { return new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8); }
                catch (IOException e) { throw new CompletionException(e); }
            });
            assertTrue(process.waitFor(30,TimeUnit.SECONDS),"Browser did not complete within its deadline");
            assertEquals(0,process.exitValue(),"Chrome must exit normally");
            String dom=output.get(2,TimeUnit.SECONDS);
            var nodes=org.jsoup.Jsoup.parse(dom).select("output#browser-result");
            String text=nodes.size()==1 ? nodes.first().text():"MISSING_OR_DUPLICATE";
            boolean safe=text.equals(PASS)||text.equals("RUNNING")||text.equals("BROWSER_FAIL: forced-failure");
            RESULTS.put(mode,Map.of("exitCode",process.exitValue(),"completed",nodes.size()==1 && nodes.first().attr("data-complete").equals("true"),"resultText",safe ? text:"UNEXPECTED_RESULT"));
            Path evidence=Path.of("target/browser-results.json");
            Files.writeString(evidence,new tools.jackson.databind.json.JsonMapper().writeValueAsString(RESULTS));
            return dom;
        } finally {
            process.descendants().forEach(child->child.destroyForcibly()); process.destroyForcibly();
            // Own fresh test profile only; never touch the developer's regular browser profile.
            try (var files=Files.walk(profile)) {
                files.sorted(Comparator.reverseOrder()).forEach(file-> { try { Files.deleteIfExists(file); } catch (IOException ignored) { } });
            }
        }
    }
    static void assertCompleted(String dom,int exitCode) {
        assertEquals(0,exitCode,"Chrome must exit normally");
        var nodes=org.jsoup.Jsoup.parse(dom).select("output#browser-result");
        assertEquals(1,nodes.size(),"Exactly one result node is required");
        var node=nodes.first();
        assertTrue(node.children().isEmpty(),"Result must be plain text, not script or markup");
        assertEquals("true",node.attr("data-complete"),"Browser flow did not finish");
        assertTrue(PASS.equals(node.text()),"Browser did not report the exact successful completion value");
    }
}
