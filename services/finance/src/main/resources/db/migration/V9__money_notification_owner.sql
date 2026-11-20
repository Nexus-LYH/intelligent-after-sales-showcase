ALTER TABLE finance_money_terminal_fact ADD department_id BIGINT NULL;
ALTER TABLE finance_money_terminal_fact ADD applicant_user_id BIGINT NULL;
ALTER TABLE finance_money_terminal_fact ADD CHECK(department_id IS NULL OR department_id>0);
ALTER TABLE finance_money_terminal_fact ADD CHECK(applicant_user_id IS NULL OR applicant_user_id>0);
