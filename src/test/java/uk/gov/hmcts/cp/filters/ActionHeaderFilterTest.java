package uk.gov.hmcts.cp.filters;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ActionHeaderFilterTest {

    private static final String ACTION_HEADER = "CPP-ACTION";

    private final ActionHeaderFilter filter = new ActionHeaderFilter();

    private HttpServletRequest filtered(final MockHttpServletRequest request) throws Exception {
        final AtomicReference<HttpServletRequest> seen = new AtomicReference<>();
        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> seen.set((HttpServletRequest) req));
        return seen.get();
    }

    private static MockHttpServletRequest request(final String method, final String servletPath) {
        final MockHttpServletRequest request = new MockHttpServletRequest(method, "/casemanagement-service" + servletPath);
        request.setContextPath("/casemanagement-service");
        request.setServletPath(servletPath);
        return request;
    }

    @Test
    void post_groupcases_should_resolve_the_create_action() throws Exception {
        final HttpServletRequest seen = filtered(request("POST", "/groupcases"));

        assertThat(seen.getHeader(ACTION_HEADER)).isEqualTo("casemanagement-service.create-group-case");
        assertThat(Collections.list(seen.getHeaders(ACTION_HEADER))).containsExactly("casemanagement-service.create-group-case");
        assertThat(Collections.list(seen.getHeaderNames())).contains(ACTION_HEADER);
    }

    @Test
    void get_groupcases_by_id_should_resolve_the_view_action() throws Exception {
        final HttpServletRequest seen = filtered(request("GET", "/groupcases/2c76c12c-b400-4f92-8caf-7e2bd0a43cd3"));

        assertThat(seen.getHeader(ACTION_HEADER)).isEqualTo("casemanagement-service.view-group-case");
    }

    @Test
    void get_groupcases_search_should_resolve_the_search_action() throws Exception {
        final HttpServletRequest seen = filtered(request("GET", "/groupcases/search"));

        assertThat(seen.getHeader(ACTION_HEADER)).isEqualTo("casemanagement-service.search-group-cases");
    }

    @Test
    void caller_supplied_action_should_be_replaced_on_a_recognised_path() throws Exception {
        final MockHttpServletRequest request = request("POST", "/groupcases");
        request.addHeader(ACTION_HEADER, "something-more-permissive");

        assertThat(filtered(request).getHeader(ACTION_HEADER)).isEqualTo("casemanagement-service.create-group-case");
    }

    @Test
    void unrecognised_requests_should_pass_through_unchanged() throws Exception {
        final MockHttpServletRequest request = request("GET", "/groupcases");
        request.addHeader(ACTION_HEADER, "caller-value");

        assertThat(filtered(request).getHeader(ACTION_HEADER)).isEqualTo("caller-value");
        assertThat(filtered(request("GET", "/actuator/health")).getHeader(ACTION_HEADER)).isNull();
    }
}
