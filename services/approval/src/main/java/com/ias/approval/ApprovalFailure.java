package com.ias.approval;

public final class ApprovalFailure extends RuntimeException {
    private final int status;
    public ApprovalFailure(int status, String code) { super(code); this.status = status; }
    public int status() { return status; }
}
