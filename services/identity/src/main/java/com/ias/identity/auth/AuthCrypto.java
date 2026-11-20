package com.ias.identity.auth;

import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

@Component
public class AuthCrypto {
    private final SecureRandom random = new SecureRandom();
    public String randomToken() {
        byte[] bytes=new byte[32]; random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    public byte[] hashToken(String token) {
        if (token==null || !token.matches("[A-Za-z0-9_-]{43}")) return null;
        byte[] bytes;
        try { bytes=Base64.getUrlDecoder().decode(token); }
        catch (IllegalArgumentException e) { return null; }
        if (bytes.length!=32 || !Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(token)) return null;
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    public boolean csrfEquals(String expected, String actual) {
        return actual!=null && actual.length()==43 && MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),actual.getBytes(StandardCharsets.US_ASCII));
    }
    public static int codePoints(String text) { return text==null ? 0 : text.codePointCount(0,text.length()); }
    public static String account(String raw) {
        if (raw==null || !raw.matches("[A-Za-z0-9._-]{1,100}")) throw new AuthFailure(400,"VALIDATION_ERROR","REUSE_PREAUTH");
        return raw.toLowerCase(Locale.ROOT);
    }
    public static String tenantCode(String raw) {
        if (raw==null || !raw.matches("[A-Za-z0-9_-]{2,32}")) throw new AuthFailure(400,"VALIDATION_ERROR","REUSE_PREAUTH");
        return raw.toUpperCase(Locale.ROOT);
    }
    public static String attemptId(String raw) {
        if (raw==null || !raw.matches("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")) throw new AuthFailure(400,"VALIDATION_ERROR","REUSE_PREAUTH");
        return raw.toLowerCase(Locale.ROOT);
    }
    public static void loginPassword(String raw) {
        int length=codePoints(raw);
        if (length<1 || length>128 || !wellFormed(raw)) throw new AuthFailure(400,"VALIDATION_ERROR","REUSE_PREAUTH");
    }
    public static void newPassword(String raw) {
        loginPassword(raw);
        if (codePoints(raw)<12) throw new AuthFailure(400,"VALIDATION_ERROR",null);
    }
    private static boolean wellFormed(String text) {
        for (int i=0; i<text.length(); i++) {
            char c=text.charAt(i);
            if (Character.isHighSurrogate(c)) { if (++i>=text.length() || !Character.isLowSurrogate(text.charAt(i))) return false; }
            else if (Character.isLowSurrogate(c)) return false;
        }
        return true;
    }
}
