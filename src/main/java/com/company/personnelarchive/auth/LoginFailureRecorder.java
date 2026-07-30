/* 登录失败记录器：统一累计失败、账号锁定和不泄露凭据的安全审计。 */
package com.company.personnelarchive.auth;

import com.company.personnelarchive.audit.AuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;

@Service
public class LoginFailureRecorder {
    private final AuthMapper mapper;
    private final AuditService audit;
    private final Clock clock;

    public LoginFailureRecorder(AuthMapper mapper, AuditService audit, Clock clock) {
        this.mapper = mapper; this.audit = audit; this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuthMapper.UserRow user) {
        if (user != null) {
            mapper.recordFailedLogin(user.id(), clock.instant().plus(Duration.ofMinutes(15)));
        }
        audit.record(user == null ? null : user.id(), "LOGIN_FAILED", "USER", user == null ? null : user.id(),
            "INVALID_CREDENTIALS", false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordLocked(AuthMapper.UserRow user) {
        audit.record(user.id(), "LOGIN_FAILED", "USER", user.id(), "ACCOUNT_LOCKED", false);
    }
}
