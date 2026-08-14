-- Rotate the fixed administrator without retaining its plaintext password or changing its user id.
UPDATE sys_user
SET username = 'hr_dept',
    normalized_username = 'hr_dept',
    password_hash = '$2a$12$5VEWfSi2yfCd/o4my8fb.ObFiWLe6b4EzIs5ZYPCXM7oTN6dF8VH.',
    display_name = '人资部门',
    is_enabled = 1,
    failed_login_count = 0,
    locked_until = NULL,
    password_changed_at = CURRENT_TIMESTAMP(6),
    updated_at = CURRENT_TIMESTAMP(6)
WHERE tenant_id = (SELECT id FROM sys_tenant WHERE tenant_code = 'DEFAULT')
  AND normalized_username IN ('hr_6298bc833379', 'hr_dept');

UPDATE sys_user_session user_session
JOIN sys_user target
  ON target.id = user_session.user_id
 AND target.tenant_id = user_session.tenant_id
SET user_session.revoked_at = CURRENT_TIMESTAMP(6),
    user_session.revoked_by = target.id,
    user_session.revoke_reason = 'DEFAULT_ADMIN_CREDENTIAL_ROTATION'
WHERE target.tenant_id = (SELECT id FROM sys_tenant WHERE tenant_code = 'DEFAULT')
  AND target.normalized_username = 'hr_dept'
  AND user_session.revoked_at IS NULL;

INSERT INTO sys_user_role (tenant_id, user_id, role_id)
VALUES (
    (SELECT id FROM sys_tenant WHERE tenant_code = 'DEFAULT'),
    (SELECT id FROM sys_user
     WHERE tenant_id = (SELECT id FROM sys_tenant WHERE tenant_code = 'DEFAULT')
       AND normalized_username = 'hr_dept'),
    (SELECT id FROM sys_role
     WHERE tenant_id = (SELECT id FROM sys_tenant WHERE tenant_code = 'DEFAULT')
       AND role_code = 'ADMIN')
) AS new_assignment
ON DUPLICATE KEY UPDATE role_id = new_assignment.role_id;

DELETE user_role
FROM sys_user_role user_role
JOIN sys_user target
  ON target.id = user_role.user_id
 AND target.tenant_id = user_role.tenant_id
JOIN sys_role assigned_role
  ON assigned_role.id = user_role.role_id
 AND assigned_role.tenant_id = user_role.tenant_id
WHERE target.tenant_id = (SELECT id FROM sys_tenant WHERE tenant_code = 'DEFAULT')
  AND target.normalized_username = 'hr_dept'
  AND assigned_role.role_code <> 'ADMIN';
