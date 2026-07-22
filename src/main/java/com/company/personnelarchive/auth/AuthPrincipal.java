/* 认证主体：只携带授权需要的用户、租户、用户名和角色，不携带敏感档案数据。 */
package com.company.personnelarchive.auth;

import java.util.Set;

public record AuthPrincipal(long userId, long tenantId, String username, Set<String> roles) {}
