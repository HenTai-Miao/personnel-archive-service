/* 初始管理员引导：仅在空用户库且显式提供环境变量时创建首个 ADMIN。 */
package com.company.personnelarchive.auth;

import com.company.personnelarchive.configuration.ArchiveProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.Locale;

@Component
public class AdminBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);
    private final AdminBootstrapMapper mapper;
    private final ArchiveProperties properties;
    private final PasswordEncoder encoder;

    public AdminBootstrap(AdminBootstrapMapper mapper, ArchiveProperties properties, PasswordEncoder encoder) {
        this.mapper = mapper; this.properties = properties; this.encoder = encoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        var bootstrap = properties.bootstrapAdmin();
        boolean hasUsername = bootstrap.username() != null && !bootstrap.username().isBlank();
        boolean hasPassword = bootstrap.password() != null && !bootstrap.password().isBlank();
        if (!hasUsername && !hasPassword) return;
        if (!hasUsername || !hasPassword || bootstrap.password().length() < 12) {
            throw new IllegalStateException("Bootstrap administrator requires username and a password of at least 12 characters");
        }
        if (mapper.countUsers(properties.tenantId()) != 0) return;
        Long roleId = mapper.findAdminRole(properties.tenantId());
        if (roleId == null) throw new IllegalStateException("ADMIN role is not initialized");
        AdminBootstrapMapper.UserInsert user = new AdminBootstrapMapper.UserInsert();
        user.tenantId = properties.tenantId(); user.username = bootstrap.username().strip();
        user.normalizedUsername = Normalizer.normalize(user.username, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        user.passwordHash = encoder.encode(bootstrap.password());
        user.displayName = bootstrap.displayName() == null || bootstrap.displayName().isBlank() ? "初始管理员" : bootstrap.displayName().strip();
        mapper.insertUser(user);
        mapper.insertRole(properties.tenantId(), user.id, roleId);
        log.info("Initial administrator created, userId={}", user.id);
    }
}
