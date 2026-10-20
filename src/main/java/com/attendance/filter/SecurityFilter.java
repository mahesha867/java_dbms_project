package com.attendance.filter;

import com.attendance.model.User;
import com.attendance.util.SessionUtil;
import jakarta.servlet.*;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Centralized Server-Side Security and Role Authorization Filter.
 * Enforces:
 * 1. Authentication boundary: blocks unauthenticated requests to protected routes.
 * 2. Role-based access control (RBAC):
 *    - Teacher routes (/teacher/*, /qr/generate, /qr/active, /reports) restricted to TEACHER / ADMIN.
 *    - Student routes (/student/*, /qr/scan) restricted to STUDENT.
 *    - Admin routes (/admin/*) restricted to ADMIN.
 * 3. Enforces HTTP 403 Forbidden on role mismatch (never silent redirects).
 */
@WebFilter(filterName = "SecurityFilter", urlPatterns = {"/*"})
public class SecurityFilter implements Filter {

    private static final List<String> PUBLIC_EXTENSIONS = Arrays.asList(
        ".css", ".js", ".png", ".jpg", ".jpeg", ".svg", ".ico", ".woff", ".woff2", ".ttf"
    );

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {}

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) res;

        String path = request.getRequestURI().substring(request.getContextPath().length());

        // 1. Static Assets & Welcome page
        if (isStaticAsset(path)) {
            chain.doFilter(req, res);
            return;
        }

        // 2. Public Endpoints
        if (path.equals("/login") || path.equals("/logout") || path.equals("/") || path.isEmpty()) {
            chain.doFilter(req, res);
            return;
        }

        // 3. Authentication Check
        User user = SessionUtil.getLoggedInUser(request);
        if (user == null) {
            String acceptHeader = request.getHeader("Accept");
            if (acceptHeader != null && acceptHeader.contains("application/json")) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.getWriter().write("{\"success\":false,\"message\":\"Authentication required. Please log in.\"}");
                return;
            }
            response.sendRedirect(request.getContextPath() + "/login?msg=auth_required");
            return;
        }

        String role = user.getRole() == null ? "" : user.getRole().toUpperCase();

        // 4. Role Authorization: Admin Only Routes
        if (path.startsWith("/admin")) {
            if (!"ADMIN".equals(role)) {
                sendForbidden(response, "Access Denied: Administrator role required.");
                return;
            }
        }

        // 5. Role Authorization: Teacher / Faculty Routes
        if (path.startsWith("/teacher") || path.startsWith("/qr/generate") || path.startsWith("/qr/active") || 
            path.startsWith("/qr/close") || path.startsWith("/qr/reopen") || path.equals("/reports")) {
            if (!"TEACHER".equals(role) && !"ADMIN".equals(role)) {
                sendForbidden(response, "Access Denied: Faculty access required. Students cannot access faculty resources.");
                return;
            }
        }

        // 6. Role Authorization: Student Routes
        if (path.startsWith("/student") || path.equals("/qr/scan")) {
            if (!"STUDENT".equals(role)) {
                sendForbidden(response, "Access Denied: Student access required. Faculty members cannot access student scanning routes.");
                return;
            }
        }

        // Authorized: continue filter chain
        chain.doFilter(req, res);
    }

    private boolean isStaticAsset(String path) {
        if (path.startsWith("/css/") || path.startsWith("/js/") || path.startsWith("/images/")) {
            return true;
        }
        for (String ext : PUBLIC_EXTENSIONS) {
            if (path.endsWith(ext)) return true;
        }
        return false;
    }

    private void sendForbidden(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("text/html;charset=UTF-8");
        response.getWriter().write(
            "<!DOCTYPE html><html><head><title>403 Forbidden</title>" +
            "<style>body{font-family:system-ui,-apple-system,sans-serif;margin:4rem auto;max-width:560px;padding:2rem;" +
            "border:1px solid #e2e8f0;border-radius:12px;box-shadow:0 10px 15px -3px rgba(0,0,0,0.1);color:#1e293b;}" +
            "h1{color:#582c83;font-size:1.75rem;margin-top:0;}p{line-height:1.6;color:#64748b;}" +
            ".btn{display:inline-block;padding:0.6rem 1.25rem;background:#582c83;color:#ffffff;text-decoration:none;" +
            "border-radius:6px;font-weight:600;margin-top:1rem;}</style></head><body>" +
            "<h1>403 — Access Denied</h1>" +
            "<p>" + message + "</p>" +
            "<a href='javascript:history.back()' class='btn'>Go Back</a>" +
            "</body></html>"
        );
    }

    @Override
    public void destroy() {}
}
