/* 时间配置：提供统一可替换 Clock，保证年龄、锁定和令牌过期测试可重复。 */
package com.company.personnelarchive.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class TimeConfiguration {
    @Bean
    Clock applicationClock() {
        return Clock.system(ZoneId.of("Asia/Shanghai"));
    }
}
