-- V3 removes import-time required fields while keeping uniqueness for supplied business identifiers.
ALTER TABLE pa_person
    DROP INDEX uk_pa_person_active_employee_no,
    DROP INDEX uk_pa_person_active_id_card_hmac,
    MODIFY COLUMN id_card_ciphertext VARBINARY(512) NULL,
    MODIFY COLUMN id_card_hmac BINARY(32) NULL,
    MODIFY COLUMN id_card_last_four CHAR(4) NULL,
    MODIFY COLUMN active_employee_no VARCHAR(64) GENERATED ALWAYS AS
        (CASE WHEN is_deleted = 0 AND NULLIF(TRIM(employee_no), '') IS NOT NULL THEN employee_no ELSE NULL END) STORED,
    MODIFY COLUMN active_id_card_hmac BINARY(32) GENERATED ALWAYS AS
        (CASE WHEN is_deleted = 0 AND id_card_hmac IS NOT NULL THEN id_card_hmac ELSE NULL END) STORED,
    ADD UNIQUE KEY uk_pa_person_active_employee_no (tenant_id, active_employee_no),
    ADD UNIQUE KEY uk_pa_person_active_id_card_hmac (tenant_id, active_id_card_hmac);
