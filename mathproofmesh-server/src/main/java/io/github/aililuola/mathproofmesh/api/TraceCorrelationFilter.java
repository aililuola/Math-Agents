package io.github.aililuola.mathproofmesh.api;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class TraceCorrelationFilter extends OncePerRequestFilter {
  private static final Logger LOGGER = LoggerFactory.getLogger(TraceCorrelationFilter.class);

  @Override
  @SuppressFBWarnings(
      value = {"SERVLET_HEADER", "CRLF_INJECTION_LOGS"},
      justification =
          "Trace headers are syntax-validated and request paths pass the control-character sanitizer.")
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String header = request.getHeader("traceparent");
    if (header == null) {
      header = request.getHeader("X-Trace-Id");
    }
    try (TraceContext.Scope scope = TraceContext.bind(header)) {
      MDC.put("trace_id", scope.traceId());
      response.setHeader("X-Trace-Id", scope.traceId());
      try {
        chain.doFilter(request, response);
      } finally {
        LOGGER.info(
            "api_request method={} path={} status={} trace_id={}",
            request.getMethod(),
            ActivitySanitizer.text(request.getRequestURI(), 240),
            response.getStatus(),
            scope.traceId());
        MDC.remove("trace_id");
      }
    }
  }
}
