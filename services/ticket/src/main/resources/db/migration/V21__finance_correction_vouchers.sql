ALTER TABLE ticket_finance_voucher DROP CHECK ck_finance_voucher_purpose;
ALTER TABLE ticket_finance_voucher ADD CONSTRAINT ck_finance_voucher_purpose CHECK(purpose IN ('FINANCE_COLLECTION','FINANCE_REFUND','FINANCE_CORRECTION'));
