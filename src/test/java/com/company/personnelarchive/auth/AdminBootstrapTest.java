/* 管理员引导测试：保证只有空库加显式安全配置才会创建初始 ADMIN。 */
package com.company.personnelarchive.auth;

import com.company.personnelarchive.configuration.ArchiveProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminBootstrapTest {
    @Test
    void createsAdminOnlyWhenDatabaseIsEmptyAndCredentialsAreExplicit() {
        AdminBootstrapMapper mapper = mock(AdminBootstrapMapper.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        ArchiveProperties properties = properties(new ArchiveProperties.BootstrapAdmin("Admin", "explicit-password", "测试管理员"));
        when(mapper.countUsers(1)).thenReturn(0L);
        when(mapper.findAdminRole(1)).thenReturn(3L);
        when(encoder.encode("explicit-password")).thenReturn("adaptive-hash");
        doAnswer(invocation -> { ((AdminBootstrapMapper.UserInsert) invocation.getArgument(0)).id = 8L; return 1; })
            .when(mapper).insertUser(any());

        new AdminBootstrap(mapper, properties, encoder).run(mock(ApplicationArguments.class));

        verify(mapper).insertRole(1, 8, 3);
    }

    @Test
    void doesNothingWithoutExplicitCredentials() {
        AdminBootstrapMapper mapper = mock(AdminBootstrapMapper.class);
        AdminBootstrap bootstrap = new AdminBootstrap(mapper,
            properties(new ArchiveProperties.BootstrapAdmin("", "", "初始管理员")), mock(PasswordEncoder.class));
        bootstrap.run(mock(ApplicationArguments.class));
        verify(mapper, never()).countUsers(1);
    }

    private static ArchiveProperties properties(ArchiveProperties.BootstrapAdmin admin) {
        return new ArchiveProperties(1,
            new ArchiveProperties.Security(Duration.ofMinutes(15), Duration.ofDays(7), 1000, "x".repeat(32), "h".repeat(32),
                "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="),
            new ArchiveProperties.Excel(12_582_912, 5_242_880, 500, "classpath:/excel/personnel-archive-template-v1.xlsx"), admin);
    }
}
