package com.chanakya.hsapi.common.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@Component
@Order(0)
public class RequestIdFilter extends OncePerRequestFilter {

    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String REQUEST_ID_ATTR = "requestId";
    private static final Pattern VALID_REQUEST_ID = Pattern.compile("^[a-zA-Z0-9\\-]{1,64}$");

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank() || !VALID_REQUEST_ID.matcher(requestId).matches()) {
            requestId = UUID.randomUUID().toString();
        }

        request.setAttribute(REQUEST_ID_ATTR, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);

        MDC.put(REQUEST_ID_ATTR, requestId);
        MDC.put("origin", request.getHeader("Origin"));
        MDC.put("remoteAddr", request.getRemoteAddr());
        try {
            log.debug("Incoming request: {} {} origin={} remoteAddr={}",
                    request.getMethod(), request.getRequestURI(),
                    request.getHeader("Origin"), request.getRemoteAddr());
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(REQUEST_ID_ATTR);
            MDC.remove("origin");
            MDC.remove("remoteAddr");
        }
    }
}
