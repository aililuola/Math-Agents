package io.github.aililuola.mathproofmesh.desktop;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** HttpOnly-cookie authentication for the loopback desktop origin. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@ConditionalOnProperty(name = "mathproofmesh.desktop.enabled", havingValue = "true")
public final class DesktopSessionFilter extends OncePerRequestFilter {
  static final String COOKIE = "mpm_desktop_session";
  static final String AUTHENTICATED = "mathproofmesh.desktop.authenticated";
  private final byte[] expected;

  public DesktopSessionFilter(@Value("${mathproofmesh.desktop.session-token}") String token) {
    if (token == null || token.length() < 32) {
      throw new IllegalArgumentException("desktop session token is too short");
    }
    expected = token.getBytes(StandardCharsets.UTF_8);
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return "/health".equals(request.getRequestURI());
  }

  @Override
  @SuppressFBWarnings(
      value = "SERVLET_PARAMETER",
      justification = "The bootstrap token is compared in constant time and is never logged or persisted.")
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String supplied = cookie(request);
    if ("/".equals(request.getRequestURI()) && request.getParameter("token") != null) {
      supplied = request.getParameter("token");
    }
    byte[] actual =
        supplied == null ? new byte[0] : supplied.getBytes(StandardCharsets.UTF_8);
    if (actual.length != expected.length || !MessageDigest.isEqual(expected, actual)) {
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setContentType("application/json");
      response.getWriter().write("{\"detail\":\"desktop session required\"}");
      return;
    }
    request.setAttribute(AUTHENTICATED, Boolean.TRUE);
    setSecurityHeaders(response, request.getRequestURI());
    chain.doFilter(request, response);
  }

  @SuppressFBWarnings(
      value = "COOKIE_USAGE",
      justification =
          "The cookie carries only an ephemeral 256-bit process token and is HttpOnly,"
              + " SameSite=Strict, loopback-only, and never persisted.")
  private static String cookie(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) {
      return null;
    }
    for (Cookie cookie : cookies) {
      if (COOKIE.equals(cookie.getName())) {
        return cookie.getValue();
      }
    }
    return null;
  }

  private static void setSecurityHeaders(HttpServletResponse response, String path) {
    response.setHeader("Cache-Control", path.startsWith("/api/") ? "no-store" : "no-cache");
    response.setHeader(
        "Content-Security-Policy",
        "default-src 'self'; img-src 'self' data:; style-src 'self'; "
            + "script-src 'self'; connect-src 'self'; frame-ancestors 'none'; "
            + "base-uri 'none'; form-action 'self'; object-src 'none'");
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("Referrer-Policy", "no-referrer");
    response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
  }
}
