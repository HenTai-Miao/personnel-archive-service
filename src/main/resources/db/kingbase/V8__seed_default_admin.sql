-- Seed the explicitly approved initial administrator without retaining its plaintext password.
INSERT INTO sys_user
    (tenant_id, username, normalized_username, password_hash, display_name,
     is_enabled, failed_login_count, locked_until)
VALUES
    ((SELECT id FROM sys_tenant WHERE tenant_code = 'DEFAULT'),
     'hr_6298bc833379',
     'hr_6298bc833379',
     '$2a$12$ZeUKR0IcwyMU1Wye4d6T9Oc/NG3ZRF3a69JSHnnXiPrHuLqxI/nBi',
     '人资部门',
     1,
     0,
     NULL)
ON CONFLICT (tenant_id, normalized_username) DO UPDATE SET
    username = EXCLUDED.username,
    password_hash = EXCLUDED.password_hash,
    display_name = EXCLUDED.display_name,
    is_enabled = 1,
    failed_login_count = 0,
    locked_until = NULL,
    password_changed_at = CURRENT_TIMESTAMP(6),
    updated_at = CURRENT_TIMESTAMP(6);

INSERT INTO sys_user_role (tenant_id, user_id, role_id)
VALUES (
    (SELECT id FROM sys_tenant WHERE tenant_code = 'DEFAULT'),
    (SELECT id FROM sys_user
     WHERE tenant_id = (SELECT id FROM sys_tenant WHERE tenant_code = 'DEFAULT')
       AND normalized_username = 'hr_6298bc833379'),
    (SELECT id FROM sys_role
     WHERE tenant_id = (SELECT id FROM sys_tenant WHERE tenant_code = 'DEFAULT')
       AND role_code = 'ADMIN')
)
ON CONFLICT (tenant_id, user_id, role_id) DO NOTHING;
