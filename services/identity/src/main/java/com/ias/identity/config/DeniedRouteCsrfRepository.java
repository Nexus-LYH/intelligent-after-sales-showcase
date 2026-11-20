package com.ias.identity.config;

import com.ias.identity.auth.AuthCrypto;
import jakarta.servlet.http.*;
import org.springframework.security.web.csrf.*;

/** Unimplemented write routes remain rejected, without generating a second HttpSession/JSESSIONID. */
final class DeniedRouteCsrfRepository implements CsrfTokenRepository {
    private final AuthCrypto crypto=new AuthCrypto();
    @Override public CsrfToken generateToken(HttpServletRequest request) { return new DefaultCsrfToken("X-CSRF-TOKEN","_csrf",crypto.randomToken()); }
    @Override public void saveToken(CsrfToken token,HttpServletRequest request,HttpServletResponse response) { /* No token is issued for denied routes. */ }
    @Override public CsrfToken loadToken(HttpServletRequest request) { return null; }
}
