package com.ias.identity.auth;

public final class AuthFailure extends RuntimeException {
    public final int status;
    public final String code, recovery;
    public AuthFailure(int status, String code, String recovery) {
        super(code);
        this.status=status; this.code=code; this.recovery=recovery;
    }
    public static AuthFailure invalid() { return new AuthFailure(401,"SESSION_INVALID","NEW_PREAUTH"); }
    public static AuthFailure expired() { return new AuthFailure(401,"PREAUTH_EXPIRED","NEW_PREAUTH"); }
    public static AuthFailure repeated() { return new AuthFailure(409,"LOGIN_ATTEMPT_REPEATED","VERIFY_ATTEMPT"); }
}
