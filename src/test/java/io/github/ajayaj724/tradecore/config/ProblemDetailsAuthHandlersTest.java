package io.github.ajayaj724.tradecore.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.json.JsonMapper;

class ProblemDetailsAuthHandlersTest {

    private final ProblemDetailsAuthHandlers handlers = new ProblemDetailsAuthHandlers(new JsonMapper());

    @Test
    void accessDeniedRendersForbiddenProblemJson() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/anything");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handlers.handle(request, response, new AccessDeniedException("denied"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).isEqualTo("application/problem+json;charset=UTF-8");
        assertThat(response.getContentAsString()).contains("Access denied");
    }
}
