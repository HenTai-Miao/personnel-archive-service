/* 认证持久化映射：读取用户/角色并管理可锁定、可撤销的刷新会话。 */
package com.company.personnelarchive.auth;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.List;

@Mapper
public interface AuthMapper {
    @Select("SELECT id,tenant_id,username,password_hash,display_name,is_enabled AS enabled,failed_login_count,locked_until FROM sys_user WHERE tenant_id=#{tenantId} AND normalized_username=#{username}")
    UserRow findUser(@Param("tenantId") long tenantId, @Param("username") String username);

    @Select("SELECT id,tenant_id,username,password_hash,display_name,is_enabled AS enabled,failed_login_count,locked_until FROM sys_user WHERE id=#{userId}")
    UserRow findUserById(long userId);

    @Select("SELECT r.role_code FROM sys_role r JOIN sys_user_role ur ON ur.role_id=r.id WHERE ur.user_id=#{userId} ORDER BY r.role_code")
    List<String> findRoles(long userId);

    @Insert("INSERT INTO sys_user_session (tenant_id,user_id,refresh_token_hash,issued_at,access_expires_at,refresh_expires_at,created_at,last_used_at) VALUES (#{tenantId},#{userId},#{hash},CURRENT_TIMESTAMP(6),#{accessExpiresAt},#{refreshExpiresAt},CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))")
    int insertSession(@Param("tenantId") long tenantId, @Param("userId") long userId,
                      @Param("hash") byte[] hash, @Param("accessExpiresAt") Instant accessExpiresAt,
                      @Param("refreshExpiresAt") Instant refreshExpiresAt);

    @Select("SELECT LAST_INSERT_ID()")
    long lastInsertId();

    @Select("SELECT id,tenant_id,user_id,refresh_token_hash,refresh_expires_at,revoked_at FROM sys_user_session WHERE refresh_token_hash=#{hash} FOR UPDATE")
    SessionRow lockSession(byte[] hash);

    @Update("UPDATE sys_user_session SET revoked_at=CURRENT_TIMESTAMP(6),revoke_reason='TOKEN_ROTATED_OR_LOGOUT',last_used_at=CURRENT_TIMESTAMP(6) WHERE id=#{id} AND revoked_at IS NULL")
    int revokeSession(long id);

    @Update("UPDATE sys_user SET last_login_at=CURRENT_TIMESTAMP(6),updated_at=CURRENT_TIMESTAMP(6) WHERE id=#{userId}")
    int touchLogin(long userId);

    @Update("UPDATE sys_user SET failed_login_count=failed_login_count+1,locked_until=CASE WHEN failed_login_count+1>=5 THEN DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 15 MINUTE) ELSE locked_until END,updated_at=CURRENT_TIMESTAMP(6) WHERE id=#{userId}")
    int recordFailedLogin(long userId);

    @Update("UPDATE sys_user SET failed_login_count=0,locked_until=NULL,last_login_at=CURRENT_TIMESTAMP(6),updated_at=CURRENT_TIMESTAMP(6) WHERE id=#{userId}")
    int recordSuccessfulLogin(long userId);

    record UserRow(long id, long tenantId, String username, String passwordHash, String displayName, boolean enabled,
                   int failedLoginCount, Instant lockedUntil) {}
    record SessionRow(long id, long tenantId, long userId, byte[] refreshTokenHash, Instant refreshExpiresAt, Instant revokedAt) {}
}
