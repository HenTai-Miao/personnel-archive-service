/* 初始管理员持久化映射：检查空库并原子写入用户与 ADMIN 角色关系。 */
package com.company.personnelarchive.auth;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AdminBootstrapMapper {
    @Select("SELECT COUNT(*) FROM sys_user WHERE tenant_id=#{tenantId}")
    long countUsers(long tenantId);

    @Select("SELECT id FROM sys_role WHERE tenant_id=#{tenantId} AND role_code='ADMIN'")
    Long findAdminRole(long tenantId);

    @Insert("INSERT INTO sys_user (tenant_id,username,normalized_username,password_hash,display_name) VALUES (#{tenantId},#{username},#{normalizedUsername},#{passwordHash},#{displayName})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertUser(UserInsert user);

    @Insert("INSERT INTO sys_user_role (tenant_id,user_id,role_id) VALUES (#{tenantId},#{userId},#{roleId})")
    int insertRole(@Param("tenantId") long tenantId, @Param("userId") long userId, @Param("roleId") long roleId);

    class UserInsert {
        public Long id; public long tenantId; public String username; public String normalizedUsername;
        public String passwordHash; public String displayName;
    }
}
