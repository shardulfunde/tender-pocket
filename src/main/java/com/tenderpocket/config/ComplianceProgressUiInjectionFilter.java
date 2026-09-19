package com.tenderpocket.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Adds the compliance progress client to the bundled SPA without rebuilding its unavailable source project. */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class ComplianceProgressUiInjectionFilter extends OncePerRequestFilter {
    private static final String SCRIPT = "<script src=\"/compliance-progress.js?v=20260918-low-1\" defer></script>";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !"GET".equalsIgnoreCase(request.getMethod())
                || !("/".equals(path) || "/index.html".equals(path) || path.startsWith("/tenders/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
        filterChain.doFilter(request, wrapped);
        byte[] original = wrapped.getContentAsByteArray();
        String contentType = wrapped.getContentType();
        if (wrapped.getStatus() == HttpServletResponse.SC_OK && contentType != null
                && contentType.contains("text/html")) {
            String html = new String(original, StandardCharsets.UTF_8);
            byte[] updated = (html.contains("src=\"/compliance-progress.js") ? html
                    : html.replace("</body>", SCRIPT + "</body>"))
                    .getBytes(StandardCharsets.UTF_8);
            wrapped.setHeader("Cache-Control", "no-cache");
            wrapped.resetBuffer();
            wrapped.setContentLength(updated.length);
            wrapped.getOutputStream().write(updated);
        }
        wrapped.copyBodyToResponse();
    }
}
