/* Bearer 过滤器：把有效短期访问令牌转换为 Spring Security 认证上下文。 */
package com.company.personnelarchive.auth;

import com.company.personnelarchive.common.RequestCorrelation;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class BearerTokenFilter extends OncePerRequestFilter {
    private final AccessTokenRegistry tokens;

    public BearerTokenFilter(AccessTokenRegistry tokens) { this.tokens = tokens; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            tokens.authenticate(header.substring(7)).ifPresent(principal -> {
                var authorities = principal.roles().stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList();
                SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(principal, null, authorities));
                request.setAttribute(RequestCorrelation.USER_ID_ATTRIBUTE, principal.userId());
            });
        }
        chain.doFilter(request, response);
    }
}
