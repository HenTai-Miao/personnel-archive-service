/* 服务启动入口：装配 Spring Boot 与类型安全的人员档案配置。 */
package com.company.personnelarchive;

import com.company.personnelarchive.configuration.ServiceLifecycleLogger;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class PersonnelArchiveApplication {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(PersonnelArchiveApplication.class);
        application.addListeners(new ServiceLifecycleLogger());
        application.run(args);
    }
}
