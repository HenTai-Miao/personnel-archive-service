package com.company.personnelarchive.auth;

import com.company.personnelarchive.audit.AuditService;
import com.company.personnelarchive.common.BusinessException;
import com.company.personnelarchive.configuration.ArchiveProperties;
import com.company.personnelarchive.support.LogCapture;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthOperationalLoggingTest {
    private static final String USERNAME = "username-secret-sentinel";
    private static final String PASSWORD = "password-secret-sentinel";
    private static final String ACCESS_TOKEN = "access-token-secret-sentinel";
    private static final String REFRESH_TOKEN = "refresh-token-secret-sentinel";
    private static final String PASSWORD_HASH = "password-hash-secret-sentinel";
    private static final Instant NOW = Instant.parse("2026-08-13T08:00:00Z");

    @Test
    void logsSuccessfulLoginWithOnlyTheInternalUserId() {
        Fixture fixture = new Fixture();
        fixture.stubEnabledUser(fixture.user(null));

        try (var logs = LogCapture.attach(AuthService.class)) {
            fixture.service.login(USERNAME, PASSWORD);

            assertThat(logs.joined())
                .contains("event=auth_login_started", "event=auth_login_completed", "userId=7", "durationMs=");
            assertRedacted(logs.joined());
        }
    }

    @Test
    void logsUnknownInvalidCredentialsWithoutTheSuppliedUsername() {
        Fixture fixture = new Fixture();

        try (var logs = LogCapture.attach(AuthService.class)) {
            assertThatThrownBy(() -> fixture.service.login(USERNAME, PASSWORD))
                .isInstanceOf(BusinessException.class);

            assertThat(logs.joined())
                .contains("event=auth_login_started", "event=auth_login_failed",
                    "userId=unknown", "code=INVALID_CREDENTIALS", "durationMs=");
            assertRedacted(logs.joined());
        }
    }

    @Test
    void logsLockedAccountByInternalIdWithoutCredentials() {
        Fixture fixture = new Fixture();
        fixture.stubEnabledUser(fixture.user(NOW.plusSeconds(60)));

        try (var logs = LogCapture.attach(AuthService.class)) {
            assertThatThrownBy(() -> fixture.service.login(USERNAME, PASSWORD))
                .isInstanceOf(BusinessException.class);

            assertThat(logs.joined())
                .contains("event=auth_login_failed", "userId=7", "code=ACCOUNT_LOCKED", "durationMs=");
            assertRedacted(logs.joined());
        }
    }

    @Test
    void logsRejectedRefreshWithoutTheToken() {
        Fixture fixture = new Fixture();

        try (var logs = LogCapture.attach(AuthService.class)) {
            assertThatThrownBy(() -> fixture.service.refresh(REFRESH_TOKEN))
                .isInstanceOf(BusinessException.class);

            assertThat(logs.joined())
                .contains("event=auth_refresh_started", "event=auth_refresh_failed",
                    "userId=unknown", "code=UNAUTHORIZED", "durationMs=");
            assertRedacted(logs.joined());
        }
    }

    @Test
    void logsSuccessfulRefreshByInternalUserId() {
        Fixture fixture = new Fixture();
        fixture.stubEnabledUser(fixture.user(null));
        when(fixture.mapper.lockSession(any(byte[].class))).thenReturn(
            new AuthMapper.SessionRow(19L, 1L, 7L, new byte[]{1}, NOW.plus(Duration.ofDays(1)), null));

        try (var logs = LogCapture.attach(AuthService.class)) {
            fixture.service.refresh(REFRESH_TOKEN);

            assertThat(logs.joined())
                .contains("event=auth_refresh_started", "event=auth_refresh_completed", "userId=7", "durationMs=");
            assertRedacted(logs.joined());
        }
    }

    @Test
    void logsLogoutByInternalUserIdWithoutEitherToken() {
        Fixture fixture = new Fixture();
        AuthPrincipal principal = new AuthPrincipal(7L, 1L, USERNAME, Set.of("ADMIN"));

        try (var logs = LogCapture.attach(AuthService.class)) {
            fixture.service.logout(ACCESS_TOKEN, REFRESH_TOKEN, principal);

            assertThat(logs.joined())
                .contains("event=auth_logout_started", "event=auth_logout_completed", "userId=7", "durationMs=");
            assertRedacted(logs.joined());
        }
    }

    private static void assertRedacted(String logText) {
        assertThat(logText).doesNotContain(USERNAME, PASSWORD, ACCESS_TOKEN, REFRESH_TOKEN, PASSWORD_HASH,
            "display-name-secret-sentinel", "token-pepper-secret-sentinel");
    }

    private static final class Fixture {
        private final AuthMapper mapper = mock(AuthMapper.class);
        private final AccessTokenRegistry accessTokens = mock(AccessTokenRegistry.class);
        private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        private final AuditService audit = mock(AuditService.class);
        private final LoginFailureRecorder loginFailures = mock(LoginFailureRecorder.class);
        private final ArchiveProperties properties = new ArchiveProperties(1,
            new ArchiveProperties.Security(Duration.ofMinutes(15), Duration.ofDays(7), 100,
                "token-pepper-secret-sentinel", "unused-hmac", "unused-encryption"), null, null);
        private final AuthService service;

        private Fixture() {
            Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
            doAnswer(invocation -> {
                AuthMapper.SessionInsert row = invocation.getArgument(0);
                row.id = 29L;
                return 1;
            }).when(mapper).insertSession(any(AuthMapper.SessionInsert.class));
            when(accessTokens.issue(any(AuthPrincipal.class), eq(29L)))
                .thenReturn(new AccessTokenRegistry.IssuedToken(ACCESS_TOKEN, NOW.plus(Duration.ofMinutes(15))));
            service = new AuthService(mapper, accessTokens, passwordEncoder, properties, clock, audit, loginFailures);
        }

        private AuthMapper.UserRow user(Instant lockedUntil) {
            return new AuthMapper.UserRow(7L, 1L, USERNAME, PASSWORD_HASH,
                "display-name-secret-sentinel", true, 0, lockedUntil);
        }

        private void stubEnabledUser(AuthMapper.UserRow user) {
            when(mapper.findUser(1L, USERNAME)).thenReturn(user);
            when(mapper.findUserById(7L)).thenReturn(user);
            when(mapper.findRoles(7L)).thenReturn(List.of("ADMIN"));
            when(passwordEncoder.matches(PASSWORD, PASSWORD_HASH)).thenReturn(true);
        }
    }
}
