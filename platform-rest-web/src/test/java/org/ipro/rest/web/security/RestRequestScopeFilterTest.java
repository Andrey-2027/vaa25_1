package org.ipro.rest.web.security;

import jakarta.servlet.ServletException;
import org.ipro.rest.web.rls.RestRequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-REST-READ-4 §4.2/§5.2: the first REST-chain filter marks the request, binds request
 * attributes for the duration and removes both on success and on every error path.
 */
class RestRequestScopeFilterTest {

    private final RestRequestScopeFilter filter = new RestRequestScopeFilter(
        new org.ipro.rest.web.problem.RestProblemWriter(new org.ipro.rest.web.codec.RestJsonCodec(
            tools.jackson.databind.json.JsonMapper.builder().build())));

    @AfterEach
    void clearAttributes() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void marksTheRequestAndCleansUpOnSuccess() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/specifications");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean activeInside = new AtomicBoolean();
        AtomicBoolean attributesBoundInside = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> {
            activeInside.set(RestRequestContext.isActive());
            attributesBoundInside.set(RequestContextHolder.getRequestAttributes() != null);
        });

        assertThat(activeInside).isTrue();
        assertThat(attributesBoundInside).isTrue();
        assertThat(RestRequestContext.isActive()).isFalse();
        assertThat(RequestContextHolder.getRequestAttributes()).isNull();
    }

    @Test
    void cleansUpOnErrorPaths() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/specifications");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            throw new ServletException("boom");
        });
        assertThat(response.getStatus()).isEqualTo(500);

        assertThat(RestRequestContext.isActive()).isFalse();
        assertThat(RequestContextHolder.getRequestAttributes()).isNull();
    }

    @Test
    void keepsPreExistingRequestAttributesOwnedByAnotherFilter() throws Exception {
        ServletRequestAttributes existing =
            new ServletRequestAttributes(new MockHttpServletRequest("GET", "/api/v1/specifications"));
        RequestContextHolder.setRequestAttributes(existing);

        filter.doFilter(existing.getRequest(), new MockHttpServletResponse(), (req, res) -> {
        });

        assertThat(RequestContextHolder.getRequestAttributes()).isSameAs(existing);
        assertThat(RestRequestContext.isActive()).isFalse();
    }

    @Test
    void completesOwnedRequestScopeOnSuccessAndFailure() throws Exception {
        for (boolean fail : new boolean[]{false, true}) {
            AtomicBoolean destroyed = new AtomicBoolean();
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/specifications"),
                    response, (req, res) -> {
                        RequestContextHolder.currentRequestAttributes().registerDestructionCallback(
                            "cache", () -> destroyed.set(true),
                            org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST);
                        if (fail) throw new ServletException("failure");
                    });
            assertThat(response.getStatus()).isEqualTo(fail ? 500 : 200);
            assertThat(destroyed).isTrue();
            assertThat(RequestContextHolder.getRequestAttributes()).isNull();
        }
    }
}
