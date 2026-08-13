package com.company.personnelarchive.configuration;

import com.company.personnelarchive.auth.BearerTokenFilter;
import com.company.personnelarchive.common.ApiError;
import com.company.personnelarchive.common.ErrorCode;
import com.company.personnelarchive.common.RequestCorrelation;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.security.config.Customizer;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

@Configuration
@EnableMethodSecurity
/**
 * HTTP 安全总入口：API 无状态、默认拒绝，仅登录/刷新匿名开放，导入再叠加角色限制。
 */
public class SecurityConfiguration {
    @Bean
    // cost 12 在当前部署中提供较强的自适应密码哈希；调整前应做目标机器基准测试。
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, BearerTokenFilter filter, JsonMapper mapper) throws Exception {
        // 客户端使用 Authorization Bearer 且不使用 Cookie 会话，因此关闭基于 Cookie 场景的 CSRF。
        http.csrf(csrf -> csrf.disable())
            .cors(Customizer.withDefaults())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/auth/login", "/api/v1/auth/refresh").permitAll()
                .requestMatchers("/api/v1/imports/**").hasAnyRole("ADMIN", "ARCHIVE_EDITOR")
                .requestMatchers(HttpMethod.POST, "/api/v1/persons/*/deletion")
                    .hasAnyRole("ADMIN", "ARCHIVE_EDITOR")
                .requestMatchers("/api/v1/**").authenticated()
                .anyRequest().denyAll())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, exception) -> write(response, mapper, ErrorCode.UNAUTHORIZED))
                .accessDeniedHandler((request, response, exception) -> write(response, mapper, ErrorCode.FORBIDDEN)))
            .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // 打包后的本地 Electron 页面 origin 为 null；通配 loopback 仅服务本地 Vite 开发。
        configuration.setAllowedOrigins(List.of("null"));
        configuration.setAllowedOriginPatterns(List.of("http://localhost:*", "http://127.0.0.1:*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        configuration.setExposedHeaders(List.of("Content-Disposition", RequestCorrelation.RESPONSE_HEADER));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    private static void write(HttpServletResponse response, JsonMapper mapper, ErrorCode code) throws java.io.IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getOutputStream(), ApiError.withRequestId(
            code, code.message(), RequestCorrelation.currentIdOrNew()));
    }
}
