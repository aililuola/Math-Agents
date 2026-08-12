package io.github.aililuola.mathproofmesh.api;

import io.github.aililuola.mathproofmesh.api.RunApiService.ApiBusyException;
import io.github.aililuola.mathproofmesh.api.RunApiService.ApiNotFoundException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public final class ApiExceptionHandler {
  @ExceptionHandler(ApiNotFoundException.class)
  ResponseEntity<Map<String, String>> notFound(ApiNotFoundException exception) {
    return response(HttpStatus.BAD_REQUEST, exception.getMessage());
  }

  @ExceptionHandler(ApiBusyException.class)
  ResponseEntity<Map<String, String>> busy(ApiBusyException exception) {
    return response(HttpStatus.TOO_MANY_REQUESTS, exception.getMessage());
  }

  @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
  ResponseEntity<Map<String, String>> invalid(Exception exception) {
    return response(HttpStatus.BAD_REQUEST, "request payload is invalid");
  }

  @ExceptionHandler(RuntimeException.class)
  ResponseEntity<Map<String, String>> failure(RuntimeException exception) {
    return response(HttpStatus.INTERNAL_SERVER_ERROR, "request failed");
  }

  private static ResponseEntity<Map<String, String>> response(HttpStatus status, String detail) {
    return ResponseEntity.status(status)
        .body(
            Map.of(
                "detail", ActivitySanitizer.text(detail, 400),
                "trace_id", TraceContext.currentOrCreate()));
  }
}
