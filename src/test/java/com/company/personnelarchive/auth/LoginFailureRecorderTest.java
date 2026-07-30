/* 登录失败测试：验证失败累计、锁定时限和不泄露敏感凭据的审计。 */
package com.company.personnelarchive.auth;

import com.company.personnelarchive.audit.AuditService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class LoginFailureRecorderTest {
    @Test
    void updatesCounterAndAuditsKnownUser() {
        AuthMapper mapper = mock(AuthMapper.class);
        AuditService audit = mock(AuditService.class);
        Instant now = Instant.parse("2026-07-30T01:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        AuthMapper.UserRow user = new AuthMapper.UserRow(7, 1, "tester", "hash", "测试用户", true, 0, (Instant) null);
        new LoginFailureRecorder(mapper, audit, clock).record(user);
        verify(mapper).recordFailedLogin(7, now.plus(Duration.ofMinutes(15)));
        verify(audit).record(7L, "LOGIN_FAILED", "USER", 7L, "INVALID_CREDENTIALS", false);
    }
}
