package io.github.aililuola.mathproofmesh.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public final class RequestSafetyFilter extends OncePerRequestFilter {
  private final long maxBodyBytes;
  private final Semaphore slots;

  public RequestSafetyFilter(
      @Value("${mathproofmesh.api.max-body-bytes:1048576}") long maxBodyBytes,
      @Value("${mathproofmesh.api.max-concurrent-requests:8}") int maxConcurrentRequests) {
    this.maxBodyBytes = Math.max(1024L, Math.min(16L * 1024 * 1024, maxBodyBytes));
    slots = new Semaphore(Math.max(1, Math.min(64, maxConcurrentRequests)), true);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    long contentLength = request.getContentLengthLong();
    if (contentLength > maxBodyBytes) {
      response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
      return;
    }
    if (!slots.tryAcquire()) {
      response.sendError(429);
      return;
    }
    try {
      chain.doFilter(request, response);
    } finally {
      slots.release();
    }
  }
}
