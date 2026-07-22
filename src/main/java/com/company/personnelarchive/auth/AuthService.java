package com.company.personnelarchive.auth;

import com.company.personnelarchive.audit.AuditService;
import com.company.personnelarchive.common.BusinessException;
import com.company.personnelarchive.common.ErrorCode;
import com.company.personnelarchive.configuration.ArchiveProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import java.text.Normalizer;
import java.util.Locale;

@Service
/**
 * 认证会话服务：校验自适应密码哈希、签发短期访问令牌、轮换刷新令牌并支持撤销。
 * 数据库只保存刷新令牌的加 pepper 哈希，任何日志和审计都不得包含原始令牌。
 */
public class AuthService {
    private final AuthMapper mapper;
    private final AccessTokenRegistry accessTokens;
    private final PasswordEncoder passwordEncoder;
    private final ArchiveProperties properties;
    private final Clock clock;
    private final AuditService audit;
    private final LoginFailureRecorder loginFailures;
    private final SecureRandom random = new SecureRandom();

    public AuthService(AuthMapper mapper, AccessTokenRegistry accessTokens, PasswordEncoder passwordEncoder,
                       ArchiveProperties properties, Clock clock, AuditService audit, LoginFailureRecorder loginFailures) {
        this.mapper = mapper;
        this.accessTokens = accessTokens;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
        this.clock = clock;
        this.audit = audit;
        this.loginFailures = loginFailures;
    }

    @Transactional
    public TokenResponse login(String username, String password) {
        AuthMapper.UserRow user = mapper.findUser(properties.tenantId(), normalizeUsername(username));
        if (user != null && user.lockedUntil() != null && user.lockedUntil().isAfter(clock.instant())) {
            loginFailures.recordLocked(user);
            throw new BusinessException(ErrorCode.ACCOUNT_LOCKED);
        }
        if (user == null || !user.enabled() || !passwordEncoder.matches(password == null ? "" : password, user.passwordHash())) {
            loginFailures.record(user);
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }
        mapper.recordSuccessfulLogin(user.id());
        TokenResponse response = issuePair(principal(user));
        audit.record(user.id(), "LOGIN", "USER", user.id(), "SUCCESS", true);
        return response;
    }

    @Transactional
    public TokenResponse refresh(String refreshToken) {
        // 行锁与“先撤销旧会话、再签发新会话”共同保证刷新令牌只能成功轮换一次。
        AuthMapper.SessionRow session = mapper.lockSession(tokenHash(refreshToken));
        if (session == null || session.revokedAt() != null || !session.refreshExpiresAt().isAfter(clock.instant())) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        AuthMapper.UserRow user = mapper.findUserById(session.userId());
        if (user == null || !user.enabled() || user.tenantId() != session.tenantId()) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        mapper.revokeSession(session.id());
        accessTokens.revokeSession(session.id());
        return issuePair(principal(user));
    }

    @Transactional
    public void logout(String accessToken, String refreshToken, AuthPrincipal principal) {
        // 同时撤销内存访问令牌和持久化刷新会话，避免只清客户端造成令牌继续可用。
        accessTokens.revoke(accessToken);
        if (refreshToken != null && !refreshToken.isBlank()) {
            AuthMapper.SessionRow session = mapper.lockSession(tokenHash(refreshToken));
            if (session != null && session.userId() == principal.userId() && session.revokedAt() == null) {
                mapper.revokeSession(session.id());
                accessTokens.revokeSession(session.id());
            }
        }
        audit.record(principal.userId(), "LOGOUT", "USER", principal.userId(), "SUCCESS", true);
    }

    private AuthPrincipal principal(AuthMapper.UserRow user) {
        return new AuthPrincipal(user.id(), user.tenantId(), user.username(), Set.copyOf(mapper.findRoles(user.id())));
    }

    private TokenResponse issuePair(AuthPrincipal principal) {
        byte[] refreshBytes = new byte[48];
        random.nextBytes(refreshBytes);
        String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(refreshBytes);
        Instant refreshExpiresAt = clock.instant().plus(properties.security().refreshTokenTtl());
        Instant accessExpiresAt = clock.instant().plus(properties.security().accessTokenTtl());
        mapper.insertSession(principal.tenantId(), principal.userId(), tokenHash(refresh), accessExpiresAt, refreshExpiresAt);
        long sessionId = mapper.lastInsertId();
        AccessTokenRegistry.IssuedToken access = accessTokens.issue(principal, sessionId);
        long expiresIn = properties.security().accessTokenTtl().toSeconds();
        AuthMapper.UserRow user = mapper.findUserById(principal.userId());
        return new TokenResponse(access.value(), refresh, expiresIn,
            new UserResponse(String.valueOf(principal.userId()), principal.username(), user.displayName(), principal.roles()));
    }

    byte[] tokenHash(String token) {
        if (token == null || token.isBlank()) throw new BusinessException(ErrorCode.UNAUTHORIZED);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // pepper 不入库，因此数据库泄露本身不足以还原或离线枚举刷新令牌。
            digest.update(properties.security().tokenPepper().getBytes(StandardCharsets.UTF_8));
            return digest.digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public record TokenResponse(String accessToken, String refreshToken, long expiresIn, UserResponse user) {}
    public record UserResponse(String id, String username, String displayName, Set<String> roles) {}

    private static String normalizeUsername(String username) {
        return username == null ? "" : Normalizer.normalize(username, Normalizer.Form.NFKC).strip().toLowerCase(Locale.ROOT);
    }
}
