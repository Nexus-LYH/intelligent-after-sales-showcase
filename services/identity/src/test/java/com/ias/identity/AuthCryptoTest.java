package com.ias.identity;

import com.ias.identity.auth.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;

class AuthCryptoTest {
    @Test void passwordEncoderPreserves128UnicodeCodePointsWithoutTruncation() {
        String password="😀".repeat(127)+"甲";
        AuthCrypto.loginPassword(password);
        var encoder=new Argon2PasswordEncoder(16,32,1,19456,2);
        String verifier=encoder.encode(password);
        assertTrue(verifier.length()<=512);
        assertTrue(verifier.startsWith("$argon2id$"));
        assertTrue(encoder.matches(password,verifier));
        assertFalse(encoder.matches("😀".repeat(127)+"乙",verifier));
        assertFalse(encoder.matches(password+" ",verifier));
        assertThrows(AuthFailure.class,()->AuthCrypto.loginPassword(password+"甲"));
        assertThrows(AuthFailure.class,()->AuthCrypto.loginPassword("\uD800"));
    }
    @Test void normalizationAndOpaqueTokensHaveSeparatePurposes() {
        assertEquals("test.user",AuthCrypto.account("Test.User"));
        assertEquals("TENANT_A",AuthCrypto.tenantCode("tenant_a"));
        assertThrows(AuthFailure.class,()->AuthCrypto.account(" test"));
        assertThrows(AuthFailure.class,()->AuthCrypto.account("Ａ"));
        assertThrows(AuthFailure.class,()->AuthCrypto.account("a".repeat(101)));
        assertEquals(100,AuthCrypto.account("a".repeat(100)).length());
        var crypto=new AuthCrypto(); String token=crypto.randomToken();
        assertEquals(43,token.length()); assertEquals(32,crypto.hashToken(token).length);
        assertArrayEquals(crypto.hashToken(token),crypto.hashToken(token));
        assertNull(crypto.hashToken("not-a-token"));
        assertTrue(crypto.csrfEquals(token,token));
        assertFalse(crypto.csrfEquals(token,crypto.randomToken()));
    }
}
