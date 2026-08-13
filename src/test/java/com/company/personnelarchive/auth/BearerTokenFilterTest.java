package com.company.personnelarchive.auth;

import com.company.personnelarchive.common.RequestCorrelation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BearerTokenFilterTest {
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void exposesOnlyTheAuthenticatedInternalUserIdToRequestLogging() throws Exception {
        AccessTokenRegistry tokens = mock(AccessTokenRegistry.class);
        AuthPrincipal principal = new AuthPrincipal(7, 1, "username-must-not-be-copied", Set.of("VIEWER"));
        when(tokens.authenticate("access-token-sentinel")).thenReturn(Optional.of(principal));
        var request = new MockHttpServletRequest("GET", "/api/v1/persons");
        request.addHeader("Authorization", "Bearer access-token-sentinel");
        var response = new MockHttpServletResponse();
        var userIdInsideChain = new AtomicReference<Object>();

        new BearerTokenFilter(tokens).doFilter(request, response, (servletRequest, servletResponse) ->
            userIdInsideChain.set(request.getAttribute(RequestCorrelation.USER_ID_ATTRIBUTE)));

        assertThat(userIdInsideChain.get()).isEqualTo(7L);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(principal);
    }
}
