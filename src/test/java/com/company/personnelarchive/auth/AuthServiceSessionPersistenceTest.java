package com.company.personnelarchive.auth;

import com.company.personnelarchive.audit.AuditService;
import com.company.personnelarchive.configuration.ArchiveProperties;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthServiceSessionPersistenceTest {
    @Test
    void issuesAccessTokenWithGeneratedSessionId() {
        AuthMapper mapper = mock(AuthMapper.class);
        AccessTokenRegistry accessTokens = mock(AccessTokenRegistry.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        AuditService audit = mock(AuditService.class);
        LoginFailureRecorder loginFailures = mock(LoginFailureRecorder.class);
        Instant now = Instant.parse("2026-07-30T01:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        ArchiveProperties properties = new ArchiveProperties(1,
            new ArchiveProperties.Security(
                Duration.ofMinutes(15), Duration.ofDays(7), 100,
                "test-pepper-that-is-at-least-thirty-two-characters", "", ""),
            null, null);
        AuthMapper.UserRow user = new AuthMapper.UserRow(
            7, 1, "tester", "hash", "测试用户", true, 0, null);
        when(mapper.findUser(1, "tester")).thenReturn(user);
        when(mapper.findUserById(7)).thenReturn(user);
        when(mapper.findRoles(7)).thenReturn(List.of("ADMIN"));
        when(passwordEncoder.matches("secret", "hash")).thenReturn(true);
        doAnswer(invocation -> {
            AuthMapper.SessionInsert row = invocation.getArgument(0);
            row.id = 42L;
            return 1;
        }).when(mapper).insertSession(any(AuthMapper.SessionInsert.class));
        when(accessTokens.issue(any(AuthPrincipal.class), eq(42L)))
            .thenReturn(new AccessTokenRegistry.IssuedToken(
                "access-token", now.plus(Duration.ofMinutes(15))));
        AuthService service = new AuthService(
            mapper, accessTokens, passwordEncoder, properties, clock, audit, loginFailures);

        service.login("tester", "secret");

        verify(accessTokens).issue(any(AuthPrincipal.class), eq(42L));
    }
}
