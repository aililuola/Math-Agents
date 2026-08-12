package io.github.aililuola.mathproofmesh.api;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public final class BearerTokenFilter extends OncePerRequestFilter {
  private final byte[] expected;

  public BearerTokenFilter(@Value("${mathproofmesh.api.token:}") String token) {
    expected = ("Bearer " + (token == null ? "" : token)).getBytes(StandardCharsets.UTF_8);
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return "/health".equals(request.getRequestURI())
        || Boolean.TRUE.equals(request.getAttribute("mathproofmesh.desktop.authenticated"));
  }

  @Override
  @SuppressFBWarnings(
      value = "SERVLET_HEADER",
      justification = "Authorization is compared in constant time and never trusted as application data.")
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String authorization = request.getHeader("Authorization");
    byte[] actual =
        authorization == null
            ? new byte[0]
            : authorization.getBytes(StandardCharsets.UTF_8);
    if (expected.length <= "Bearer ".length()
        || actual.length != expected.length
        || !MessageDigest.isEqual(expected, actual)) {
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.setContentType(MediaTypeConstants.JSON);
      response.getWriter().write("{\"detail\":\"invalid bearer token\"}");
      return;
    }
    chain.doFilter(request, response);
  }

  private static final class MediaTypeConstants {
    private static final String JSON = "application/json";
  }
}
