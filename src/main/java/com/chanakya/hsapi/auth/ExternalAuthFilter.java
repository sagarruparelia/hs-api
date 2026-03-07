package com.chanakya.hsapi.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

@Slf4j
@Component
public class ExternalAuthFilter extends OncePerRequestFilter {

    private static final String CONSUMER_ID_HEADER = "X-Consumer-Id";
    public static final String CONSUMER_ID_ATTR = "consumerId";
    public static final String SOURCE_ATTR = "source";
    private static final Pattern VALID_CONSUMER_ID = Pattern.compile("^[a-zA-Z0-9._-]{1,128}$");

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();

        if (!path.startsWith("/secure/api/") && !path.equals("/graphql")) {
            filterChain.doFilter(request, response);
            return;
        }

        logRequestOrigin(request, path);

        // If OAuth2 JWT already authenticated this request, use that identity
        Authentication existingAuth = SecurityContextHolder.getContext().getAuthentication();
        if (existingAuth != null && existingAuth.isAuthenticated()
                && !(existingAuth instanceof AnonymousAuthenticationToken)) {
            request.setAttribute(CONSUMER_ID_ATTR, existingAuth.getName());
            request.setAttribute(SOURCE_ATTR, "oauth2");
            log.info("Authenticated via OAuth2 JWT: principal={}", existingAuth.getName());
            filterChain.doFilter(request, response);
            return;
        }

        // No JWT auth — authenticate via X-Consumer-Id header (proxy / local dev)
        String consumerId = request.getHeader(CONSUMER_ID_HEADER);
        if (consumerId == null || consumerId.isBlank()) {
            log.warn("Unauthenticated request to {} from {}", path, request.getRemoteAddr());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"unauthorized\",\"message\":\"Authentication required\"}");
            return;
        }

        if (!VALID_CONSUMER_ID.matcher(consumerId).matches()) {
            log.warn("Invalid consumer ID format from {}", request.getRemoteAddr());
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"bad_request\",\"message\":\"Invalid consumer identifier\"}");
            return;
        }

        var authentication = new UsernamePasswordAuthenticationToken(consumerId, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(authentication);

        request.setAttribute(CONSUMER_ID_ATTR, consumerId);
        request.setAttribute(SOURCE_ATTR, "proxy");
        log.info("Authenticated via proxy header: consumerId={}", consumerId);

        filterChain.doFilter(request, response);
    }

    private void logRequestOrigin(HttpServletRequest request, String path) {
        String origin = request.getHeader("Origin");
        String remoteAddr = request.getRemoteAddr();
        String forwarded = request.getHeader("X-Forwarded-For");
        log.info("Secured request: path={}, origin={}, remoteAddr={}, forwarded={}",
                path, origin, remoteAddr, forwarded);
    }
}
