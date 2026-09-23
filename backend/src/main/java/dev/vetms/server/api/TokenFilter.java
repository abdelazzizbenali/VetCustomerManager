package dev.vetms.server.api;

import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * Every {@code /api/**} call except {@code /api/health} must carry the
 * per-boot bearer token (the UI reads {@code server-info.json}, written
 * into the per-user data dir on the same machine).
 */
@Component
public class TokenFilter extends OncePerRequestFilter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/") || path.equals("/api/health")) {
            chain.doFilter(request, response);
            return;
        }
        String expected = System.getProperty("vetms.token", "");
        String header = request.getHeader("Authorization");
        String query = request.getParameter("token"); // for preview links that cannot set headers
        String supplied = header != null && header.startsWith("Bearer ")
                ? header.substring(7)
                : query != null ? query : "";
        if (!expected.isEmpty() && expected.equals(supplied)) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(401);
        response.setContentType("application/json");
        response.getWriter().write(MAPPER.writeValueAsString(Map.of(
                "error", "unauthorized", "message", "missing or wrong server token")));
    }
}
