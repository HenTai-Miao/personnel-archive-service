-- MySQL 8.4 / Flyway baseline for personnel_archive.
-- The application fixes tenant_id to 1 in version 1; clients never supply it.

CREATE TABLE sys_tenant (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_code VARCHAR(64) NOT NULL,
    tenant_name VARCHAR(128) NOT NULL,
    is_enabled TINYINT(1) NOT NULL DEFAULT 1,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_tenant_code (tenant_code),
    CONSTRAINT ck_sys_tenant_enabled CHECK (is_enabled IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE sys_user (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    username VARCHAR(128) NOT NULL,
    normalized_username VARCHAR(128) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    is_enabled TINYINT(1) NOT NULL DEFAULT 1,
    failed_login_count INT NOT NULL DEFAULT 0,
    locked_until DATETIME(6) NULL,
    password_changed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_login_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_user_tenant_username (tenant_id, normalized_username),
    KEY idx_sys_user_tenant_enabled (tenant_id, is_enabled),
    CONSTRAINT fk_sys_user_tenant FOREIGN KEY (tenant_id) REFERENCES sys_tenant (id),
    CONSTRAINT ck_sys_user_enabled CHECK (is_enabled IN (0, 1)),
    CONSTRAINT ck_sys_user_failed_login_count CHECK (failed_login_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE sys_role (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    role_code VARCHAR(64) NOT NULL,
    role_name VARCHAR(128) NOT NULL,
    description VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_role_tenant_code (tenant_id, role_code),
    CONSTRAINT fk_sys_role_tenant FOREIGN KEY (tenant_id) REFERENCES sys_tenant (id),
    CONSTRAINT ck_sys_role_code CHECK (role_code IN ('ADMIN', 'ARCHIVE_EDITOR', 'VIEWER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE sys_user_role (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by BIGINT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_user_role (tenant_id, user_id, role_id),
    KEY idx_sys_user_role_role (role_id),
    KEY idx_sys_user_role_created_by (created_by),
    CONSTRAINT fk_sys_user_role_tenant FOREIGN KEY (tenant_id) REFERENCES sys_tenant (id),
    CONSTRAINT fk_sys_user_role_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
    CONSTRAINT fk_sys_user_role_role FOREIGN KEY (role_id) REFERENCES sys_role (id),
    CONSTRAINT fk_sys_user_role_created_by FOREIGN KEY (created_by) REFERENCES sys_user (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE sys_user_session (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    refresh_token_hash BINARY(32) NOT NULL,
    client_instance_id VARCHAR(128) NULL,
    issued_at DATETIME(6) NOT NULL,
    access_expires_at DATETIME(6) NOT NULL,
    refresh_expires_at DATETIME(6) NOT NULL,
    last_used_at DATETIME(6) NULL,
    revoked_at DATETIME(6) NULL,
    revoked_by BIGINT NULL,
    revoke_reason VARCHAR(255) NULL,
    replaced_by_session_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_user_session_refresh_hash (refresh_token_hash),
    KEY idx_sys_user_session_user_active (tenant_id, user_id, revoked_at, refresh_expires_at),
    KEY idx_sys_user_session_revoked_by (revoked_by),
    KEY idx_sys_user_session_replaced_by (replaced_by_session_id),
    CONSTRAINT fk_sys_user_session_tenant FOREIGN KEY (tenant_id) REFERENCES sys_tenant (id),
    CONSTRAINT fk_sys_user_session_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
    CONSTRAINT fk_sys_user_session_revoked_by FOREIGN KEY (revoked_by) REFERENCES sys_user (id),
    CONSTRAINT fk_sys_user_session_replaced_by FOREIGN KEY (replaced_by_session_id) REFERENCES sys_user_session (id),
    CONSTRAINT ck_sys_user_session_expiry CHECK (access_expires_at <= refresh_expires_at),
    CONSTRAINT ck_sys_user_session_revoke CHECK (revoked_at IS NULL OR revoke_reason IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE pa_person (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    employee_no VARCHAR(64) NOT NULL,
    person_name VARCHAR(128) NOT NULL,
    normalized_name VARCHAR(128) NOT NULL,
    id_card_ciphertext VARBINARY(512) NOT NULL,
    id_card_hmac BINARY(32) NOT NULL,
    id_card_last_four CHAR(4) NOT NULL,
    gender VARCHAR(32) NULL,
    birth_date DATE NULL,
    nation VARCHAR(64) NULL,
    native_place VARCHAR(255) NULL,
    birth_place VARCHAR(255) NULL,
    political_status VARCHAR(128) NULL,
    political_join_date DATE NULL,
    marital_status VARCHAR(64) NULL,
    health_status VARCHAR(255) NULL,
    mailing_address VARCHAR(500) NULL,
    postal_code VARCHAR(32) NULL,
    phone_number VARCHAR(64) NULL,
    declaration_signer VARCHAR(128) NULL,
    declaration_date DATE NULL,
    template_version VARCHAR(32) NOT NULL,
    snapshot_version BIGINT NOT NULL DEFAULT 1,
    previous_person_id BIGINT NULL,
    is_deleted TINYINT(1) NOT NULL DEFAULT 0,
    deleted_at DATETIME(6) NULL,
    deleted_by BIGINT NULL,
    delete_reason VARCHAR(255) NULL,
    replaced_by_person_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by BIGINT NOT NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    updated_by BIGINT NOT NULL,
    active_employee_no VARCHAR(64) GENERATED ALWAYS AS
        (CASE WHEN is_deleted = 0 THEN employee_no ELSE NULL END) STORED,
    active_id_card_hmac BINARY(32) GENERATED ALWAYS AS
        (CASE WHEN is_deleted = 0 THEN id_card_hmac ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pa_person_active_employee_no (tenant_id, active_employee_no),
    UNIQUE KEY uk_pa_person_active_id_card_hmac (tenant_id, active_id_card_hmac),
    KEY idx_pa_person_active_name (tenant_id, is_deleted, normalized_name),
    KEY idx_pa_person_previous (previous_person_id),
    KEY idx_pa_person_replaced_by (replaced_by_person_id),
    KEY idx_pa_person_deleted_by (deleted_by),
    KEY idx_pa_person_created_by (created_by),
    KEY idx_pa_person_updated_by (updated_by),
    CONSTRAINT fk_pa_person_tenant FOREIGN KEY (tenant_id) REFERENCES sys_tenant (id),
    CONSTRAINT fk_pa_person_previous FOREIGN KEY (previous_person_id) REFERENCES pa_person (id),
    CONSTRAINT fk_pa_person_replaced_by FOREIGN KEY (replaced_by_person_id) REFERENCES pa_person (id),
    CONSTRAINT fk_pa_person_deleted_by FOREIGN KEY (deleted_by) REFERENCES sys_user (id),
    CONSTRAINT fk_pa_person_created_by FOREIGN KEY (created_by) REFERENCES sys_user (id),
    CONSTRAINT fk_pa_person_updated_by FOREIGN KEY (updated_by) REFERENCES sys_user (id),
    CONSTRAINT ck_pa_person_deleted CHECK (
        (is_deleted = 0 AND deleted_at IS NULL AND deleted_by IS NULL AND delete_reason IS NULL AND replaced_by_person_id IS NULL)
        OR
        (is_deleted = 1 AND deleted_at IS NOT NULL AND deleted_by IS NOT NULL AND delete_reason IS NOT NULL)
    ),
    CONSTRAINT ck_pa_person_version CHECK (snapshot_version >= 1),
    CONSTRAINT ck_pa_person_deleted_flag CHECK (is_deleted IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE pa_employment (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    person_id BIGINT NOT NULL,
    organization_name VARCHAR(255) NULL,
    department_name VARCHAR(255) NULL,
    work_start_date DATE NULL,
    dispatched_to_bidding_company_date DATE NULL,
    position_name VARCHAR(255) NULL,
    professional_title_or_skill VARCHAR(255) NULL,
    title_or_skill_acquired_date DATE NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_pa_employment_person (tenant_id, person_id),
    KEY idx_pa_employment_org_dept (tenant_id, organization_name, department_name),
    CONSTRAINT fk_pa_employment_tenant FOREIGN KEY (tenant_id) REFERENCES sys_tenant (id),
    CONSTRAINT fk_pa_employment_person FOREIGN KEY (person_id) REFERENCES pa_person (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE pa_education (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    person_id BIGINT NOT NULL,
    education_type VARCHAR(32) NOT NULL,
    institution_name VARCHAR(255) NULL,
    department_and_major VARCHAR(255) NULL,
    education_level VARCHAR(128) NULL,
    degree_name VARCHAR(128) NULL,
    start_date DATE NULL,
    end_date DATE NULL,
    raw_text TEXT NULL,
    sort_order INT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_pa_education_order (tenant_id, person_id, education_type, sort_order),
    KEY idx_pa_education_person (person_id),
    CONSTRAINT fk_pa_education_tenant FOREIGN KEY (tenant_id) REFERENCES sys_tenant (id),
    CONSTRAINT fk_pa_education_person FOREIGN KEY (person_id) REFERENCES pa_person (id),
    CONSTRAINT ck_pa_education_type CHECK (education_type IN ('FULL_TIME', 'IN_SERVICE', 'MAIN')),
    CONSTRAINT ck_pa_education_order CHECK (
        (education_type IN ('FULL_TIME', 'IN_SERVICE') AND sort_order = 0)
        OR (education_type = 'MAIN' AND sort_order BETWEEN 1 AND 5)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE pa_work_experience (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    person_id BIGINT NOT NULL,
    start_date DATE NULL,
    end_date DATE NULL,
    organization_and_position VARCHAR(500) NULL,
    raw_text TEXT NOT NULL,
    sort_order INT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_pa_work_experience_order (tenant_id, person_id, sort_order),
    KEY idx_pa_work_experience_person (person_id),
    CONSTRAINT fk_pa_work_experience_tenant FOREIGN KEY (tenant_id) REFERENCES sys_tenant (id),
    CONSTRAINT fk_pa_work_experience_person FOREIGN KEY (person_id) REFERENCES pa_person (id),
    CONSTRAINT ck_pa_work_experience_order CHECK (sort_order BETWEEN 1 AND 16)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE pa_reward_punishment (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    person_id BIGINT NOT NULL,
    occurred_date DATE NULL,
    raw_text TEXT NOT NULL,
    sort_order INT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_pa_reward_punishment_order (tenant_id, person_id, sort_order),
    KEY idx_pa_reward_punishment_person (person_id),
    CONSTRAINT fk_pa_reward_punishment_tenant FOREIGN KEY (tenant_id) REFERENCES sys_tenant (id),
    CONSTRAINT fk_pa_reward_punishment_person FOREIGN KEY (person_id) REFERENCES pa_person (id),
    CONSTRAINT ck_pa_reward_punishment_order CHECK (sort_order BETWEEN 1 AND 4)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE pa_family_member (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    person_id BIGINT NOT NULL,
    relationship_name VARCHAR(128) NOT NULL,
    member_name VARCHAR(128) NOT NULL,
    normalized_member_name VARCHAR(128) NOT NULL,
    birth_date DATE NULL,
    political_status VARCHAR(128) NULL,
    organization_and_position VARCHAR(500) NULL,
    raw_text TEXT NULL,
    sort_order INT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_pa_family_member_order (tenant_id, person_id, sort_order),
    KEY idx_pa_family_member_name (tenant_id, normalized_member_name),
    KEY idx_pa_family_member_person (person_id),
    CONSTRAINT fk_pa_family_member_tenant FOREIGN KEY (tenant_id) REFERENCES sys_tenant (id),
    CONSTRAINT fk_pa_family_member_person FOREIGN KEY (person_id) REFERENCES pa_person (id),
    CONSTRAINT ck_pa_family_member_order CHECK (sort_order BETWEEN 1 AND 6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE pa_person_photo (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    person_id BIGINT NOT NULL,
    photo_data MEDIUMBLOB NOT NULL,
    mime_type VARCHAR(128) NOT NULL,
    original_filename VARCHAR(255) NULL,
    byte_size BIGINT NOT NULL,
    width_pixels INT NOT NULL,
    height_pixels INT NOT NULL,
    sha256 BINARY(32) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_pa_person_photo_person (tenant_id, person_id),
    KEY idx_pa_person_photo_hash (tenant_id, sha256),
    CONSTRAINT fk_pa_person_photo_tenant FOREIGN KEY (tenant_id) REFERENCES sys_tenant (id),
    CONSTRAINT fk_pa_person_photo_person FOREIGN KEY (person_id) REFERENCES pa_person (id),
    CONSTRAINT ck_pa_person_photo_size CHECK (byte_size BETWEEN 1 AND 5242880),
    CONSTRAINT ck_pa_person_photo_dimensions CHECK (width_pixels > 0 AND height_pixels > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE pa_import_batch (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    batch_status VARCHAR(32) NOT NULL,
    total_count INT NOT NULL,
    pending_count INT NOT NULL DEFAULT 0,
    imported_count INT NOT NULL DEFAULT 0,
    overwritten_count INT NOT NULL DEFAULT 0,
    skipped_count INT NOT NULL DEFAULT 0,
    failed_count INT NOT NULL DEFAULT 0,
    started_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at DATETIME(6) NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_pa_import_batch_creator (tenant_id, created_by, created_at),
    CONSTRAINT fk_pa_import_batch_tenant FOREIGN KEY (tenant_id) REFERENCES sys_tenant (id),
    CONSTRAINT fk_pa_import_batch_created_by FOREIGN KEY (created_by) REFERENCES sys_user (id),
    CONSTRAINT ck_pa_import_batch_status CHECK (batch_status IN ('PROCESSING', 'AWAITING_DECISION', 'COMPLETED', 'COMPLETED_WITH_ERRORS')),
    CONSTRAINT ck_pa_import_batch_counts CHECK (
        total_count >= 1 AND pending_count >= 0 AND imported_count >= 0
        AND overwritten_count >= 0 AND skipped_count >= 0 AND failed_count >= 0
        AND pending_count + imported_count + overwritten_count + skipped_count + failed_count <= total_count
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE pa_import_item (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    batch_id BIGINT NOT NULL,
    item_order INT NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    file_size BIGINT NOT NULL,
    file_sha256 BINARY(32) NOT NULL,
    template_version VARCHAR(32) NULL,
    item_status VARCHAR(32) NOT NULL,
    result_code VARCHAR(64) NOT NULL,
    result_message VARCHAR(500) NOT NULL,
    matched_person_id BIGINT NULL,
    matched_snapshot_version BIGINT NULL,
    imported_person_id BIGINT NULL,
    decision VARCHAR(16) NULL,
    decision_expires_at DATETIME(6) NULL,
    decided_at DATETIME(6) NULL,
    decided_by BIGINT NULL,
    processed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_pa_import_item_order (tenant_id, batch_id, item_order),
    KEY idx_pa_import_item_status (tenant_id, item_status, created_at),
    KEY idx_pa_import_item_file_hash (tenant_id, file_sha256),
    KEY idx_pa_import_item_matched_person (matched_person_id),
    KEY idx_pa_import_item_imported_person (imported_person_id),
    KEY idx_pa_import_item_decided_by (decided_by),
    CONSTRAINT fk_pa_import_item_tenant FOREIGN KEY (tenant_id) REFERENCES sys_tenant (id),
    CONSTRAINT fk_pa_import_item_batch FOREIGN KEY (batch_id) REFERENCES pa_import_batch (id),
    CONSTRAINT fk_pa_import_item_matched_person FOREIGN KEY (matched_person_id) REFERENCES pa_person (id),
    CONSTRAINT fk_pa_import_item_imported_person FOREIGN KEY (imported_person_id) REFERENCES pa_person (id),
    CONSTRAINT fk_pa_import_item_decided_by FOREIGN KEY (decided_by) REFERENCES sys_user (id),
    CONSTRAINT ck_pa_import_item_status CHECK (item_status IN ('PENDING_DECISION', 'IMPORTED', 'OVERWRITTEN', 'SKIPPED', 'FAILED')),
    CONSTRAINT ck_pa_import_item_decision CHECK (decision IS NULL OR decision IN ('OVERWRITE', 'SKIP')),
    CONSTRAINT ck_pa_import_item_file_size CHECK (file_size >= 0),
    CONSTRAINT ck_pa_import_item_pending CHECK (
        (item_status = 'PENDING_DECISION' AND matched_person_id IS NOT NULL AND matched_snapshot_version IS NOT NULL
            AND decision_expires_at IS NOT NULL AND decision IS NULL)
        OR item_status <> 'PENDING_DECISION'
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE sys_operation_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    operator_user_id BIGINT NULL,
    action_code VARCHAR(64) NOT NULL,
    resource_type VARCHAR(64) NULL,
    resource_id BIGINT NULL,
    subject_person_id BIGINT NULL,
    related_person_id BIGINT NULL,
    import_batch_id BIGINT NULL,
    import_item_id BIGINT NULL,
    outcome VARCHAR(16) NOT NULL,
    error_code VARCHAR(64) NULL,
    request_id VARCHAR(128) NOT NULL,
    http_method VARCHAR(16) NULL,
    request_path VARCHAR(255) NULL,
    safe_metadata JSON NULL,
    occurred_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_sys_operation_log_tenant_time (tenant_id, occurred_at),
    KEY idx_sys_operation_log_operator (tenant_id, operator_user_id, occurred_at),
    KEY idx_sys_operation_log_person (tenant_id, subject_person_id, occurred_at),
    KEY idx_sys_operation_log_action (tenant_id, action_code, occurred_at),
    KEY idx_sys_operation_log_batch (import_batch_id),
    KEY idx_sys_operation_log_item (import_item_id),
    CONSTRAINT fk_sys_operation_log_tenant FOREIGN KEY (tenant_id) REFERENCES sys_tenant (id),
    CONSTRAINT fk_sys_operation_log_operator FOREIGN KEY (operator_user_id) REFERENCES sys_user (id),
    CONSTRAINT fk_sys_operation_log_subject_person FOREIGN KEY (subject_person_id) REFERENCES pa_person (id),
    CONSTRAINT fk_sys_operation_log_related_person FOREIGN KEY (related_person_id) REFERENCES pa_person (id),
    CONSTRAINT fk_sys_operation_log_batch FOREIGN KEY (import_batch_id) REFERENCES pa_import_batch (id),
    CONSTRAINT fk_sys_operation_log_item FOREIGN KEY (import_item_id) REFERENCES pa_import_item (id),
    CONSTRAINT ck_sys_operation_log_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO sys_tenant (id, tenant_code, tenant_name)
VALUES (1, 'DEFAULT', '默认组织');

INSERT INTO sys_role (tenant_id, role_code, role_name, description)
VALUES
    (1, 'ADMIN', '系统管理员', '用户、权限、档案及系统管理'),
    (1, 'ARCHIVE_EDITOR', '档案编辑员', '导入、覆盖、编辑、查询及导出档案'),
    (1, 'VIEWER', '档案查看员', '查询、查看及导出档案');
