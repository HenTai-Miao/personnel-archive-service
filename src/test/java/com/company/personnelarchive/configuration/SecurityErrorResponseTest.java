package com.company.personnelarchive.configuration;

import com.company.personnelarchive.common.ErrorCode;
import com.company.personnelarchive.common.RequestCorrelation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityErrorResponseTest {
    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void includesTheCurrentRequestIdInSecurityRejections() throws Exception {
        String requestId = "f053ca98-a7e1-4e8a-a4fd-b30652a713ef";
        MDC.put(RequestCorrelation.MDC_KEY, requestId);
        var response = new MockHttpServletResponse();
        var mapper = new JsonMapper();
        Method write = SecurityConfiguration.class.getDeclaredMethod(
            "write", jakarta.servlet.http.HttpServletResponse.class, JsonMapper.class, ErrorCode.class);
        write.setAccessible(true);

        write.invoke(null, response, mapper, ErrorCode.UNAUTHORIZED);

        var json = mapper.readTree(response.getContentAsByteArray());
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(json.at("/details/requestId").asText()).isEqualTo(requestId);
    }
}
