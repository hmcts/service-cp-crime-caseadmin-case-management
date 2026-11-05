package uk.gov.hmcts.cp.filters;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves the CPP-ACTION header from the request method and path, and enforces it server-side
 * for every request this filter recognises, so the Drools authz filter always evaluates the
 * real action. A caller-supplied CPP-ACTION on a recognised request is replaced, because
 * trusting it would let a caller name a more permissive action. Requests this filter does not
 * recognise pass through unchanged.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ActionHeaderFilter extends OncePerRequestFilter {

    private static final String ACTION_HEADER = "CPP-ACTION";

    private static final Map<String, String> REQUEST_TO_ACTION = Map.of(
            "POST /groupcases", "casemanagement-service.create-group-case"
    );

    @Override
    protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response,
                                    final FilterChain filterChain) throws ServletException, IOException {

        final String servletPath = request.getServletPath();
        final String path = servletPath == null || servletPath.isEmpty() ? request.getRequestURI() : servletPath;
        final String action = REQUEST_TO_ACTION.get(request.getMethod() + " " + path);
        if (action == null) {
            filterChain.doFilter(request, response);
        } else {
            filterChain.doFilter(new ActionHeaderRequestWrapper(request, action), response);
        }
    }

    private static class ActionHeaderRequestWrapper extends HttpServletRequestWrapper {

        private final String action;

        /* default */ ActionHeaderRequestWrapper(final HttpServletRequest request, final String action) {
            super(request);
            this.action = action;
        }

        @Override
        public String getHeader(final String name) {
            return ACTION_HEADER.equalsIgnoreCase(name) ? action : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(final String name) {
            return ACTION_HEADER.equalsIgnoreCase(name)
                    ? Collections.enumeration(List.of(action))
                    : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            final List<String> names = Collections.list(super.getHeaderNames());
            if (names.stream().noneMatch(ACTION_HEADER::equalsIgnoreCase)) {
                names.add(ACTION_HEADER);
            }
            return Collections.enumeration(names);
        }
    }
}
