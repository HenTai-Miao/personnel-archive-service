/* CORS 配置测试：只允许打包 Electron origin 与受控 loopback 开发 origin。 */
package com.company.personnelarchive.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.CorsFilter;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class CorsConfigurationTest {
    @Test
    void permitsPackagedNullOriginPreflightWithoutCredentials() throws Exception {
        var source = new SecurityConfiguration().corsConfigurationSource();
        standaloneSetup(new Probe()).addFilters(new CorsFilter(source)).build()
            .perform(options("/api/v1/family-members/search")
                .header(HttpHeaders.ORIGIN, "null")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization"))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "null"))
            .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    @Test
    void exposesTheRequestIdHeaderToThePackagedClient() throws Exception {
        var source = new SecurityConfiguration().corsConfigurationSource();
        standaloneSetup(new Probe()).addFilters(new CorsFilter(source)).build()
            .perform(get("/api/v1/family-members/search")
                .header(HttpHeaders.ORIGIN, "null"))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                org.hamcrest.Matchers.containsString("X-Request-Id")));
    }

    @RestController
    static class Probe {
        @GetMapping("/api/v1/family-members/search") String get() { return "ok"; }
    }
}
