package com.company.personnelarchive.auth;

import com.company.personnelarchive.configuration.ArchiveProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
/**
 * 单实例部署使用的短期访问令牌注册表。
 * Map 键是加 pepper 的哈希，支持即时撤销且避免在服务内存结构中保存可直接使用的令牌。
 */
public class AccessTokenRegistry {
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final ArchiveProperties properties;

    public AccessTokenRegistry(Clock clock, ArchiveProperties properties) {
        this.clock = clock;
        this.properties = properties;
        if (properties.security().tokenPepper() == null || properties.security().tokenPepper().length() < 32) {
            throw new IllegalStateException("PA_TOKEN_PEPPER must contain at least 32 characters");
        }
    }

    public IssuedToken issue(AuthPrincipal principal, long sessionId) {
        // 有界容量比无上限增长更安全；容量耗尽应暴露运维问题，而不是继续消耗内存。
        evictExpired();
        if (entries.size() >= properties.security().accessMaxItems()) {
            throw new IllegalStateException("Access token registry capacity exceeded");
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt = clock.instant().plus(properties.security().accessTokenTtl());
        // 原始值只返回一次给调用方，注册表保存其不可直接使用的哈希键。
        entries.put(hash(raw), new Entry(principal, sessionId, expiresAt));
        return new IssuedToken(raw, expiresAt);
    }

    public Optional<AuthPrincipal> authenticate(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        Entry entry = entries.get(hash(raw));
        if (entry == null) return Optional.empty();
        if (!entry.expiresAt().isAfter(clock.instant())) {
            entries.remove(hash(raw));
            return Optional.empty();
        }
        return Optional.of(entry.principal());
    }

    public void revoke(String raw) {
        if (raw != null && !raw.isBlank()) entries.remove(hash(raw));
    }

    public void revokeSession(long sessionId) {
        // 刷新令牌轮换或退出时，撤销同一数据库会话派生出的所有访问令牌。
        entries.entrySet().removeIf(entry -> entry.getValue().sessionId() == sessionId);
    }

    private void evictExpired() {
        Instant now = clock.instant();
        entries.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    private String hash(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(properties.security().tokenPepper().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Entry(AuthPrincipal principal, long sessionId, Instant expiresAt) {}
    public record IssuedToken(String value, Instant expiresAt) {}
}
