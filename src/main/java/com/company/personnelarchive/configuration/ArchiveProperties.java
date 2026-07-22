/* 外部配置模型：绑定租户、Excel 限额与安全密钥/令牌生命周期等服务参数。 */
package com.company.personnelarchive.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("personnel-archive")
public record ArchiveProperties(long tenantId, Security security, Excel excel, BootstrapAdmin bootstrapAdmin) {
    public record Security(Duration accessTokenTtl, Duration refreshTokenTtl, int accessMaxItems, String tokenPepper,
                           String identityHmacKey, String identityEncryptionKey) {}
    public record Excel(long maxFileBytes, long maxPhotoBytes, int pendingMaxItems, String template) {}
    public record BootstrapAdmin(String username, String password, String displayName) {}
}
