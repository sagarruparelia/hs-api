package com.chanakya.hsapi.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class ExternalAuthFilter extends OncePerRequestFilter {

    private static final String CONSUMER_ID_HEADER = "X-Consumer-Id";
    public static final String CONSUMER_ID_ATTR = "consumerId";
    public static final String SOURCE_ATTR = "source";

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();

        if (!path.startsWith("/secure/api/") && !path.equals("/graphql")) {
            filterChain.doFilter(request, response);
            return;
        }

        String consumerId = request.getHeader(CONSUMER_ID_HEADER);
        if (consumerId == null || consumerId.isBlank()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"unauthorized\",\"message\":\"X-Consumer-Id header is required\"}");
            return;
        }

        var authentication = new UsernamePasswordAuthenticationToken(consumerId, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // Store in request attributes for downstream access (ScopedValue doesn't span filter chain)
        request.setAttribute(CONSUMER_ID_ATTR, consumerId);
        request.setAttribute(SOURCE_ATTR, "external");

        filterChain.doFilter(request, response);
    }
}
