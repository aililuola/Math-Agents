package io.github.aililuola.mathproofmesh.provider;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record HttpTransportRequest(
    URI uri,
    String method,
    Map<String, String> headers,
    byte[] body,
    Duration timeout) {

  public HttpTransportRequest {
    uri = Objects.requireNonNull(uri, "uri");
    method = Objects.requireNonNull(method, "method").strip();
    if (method.isEmpty()) {
      throw new IllegalArgumentException("method must not be blank");
    }
    headers =
        Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(headers, "headers")));
    body = Objects.requireNonNull(body, "body").clone();
    timeout = Objects.requireNonNull(timeout, "timeout");
    if (timeout.isZero() || timeout.isNegative()) {
      throw new IllegalArgumentException("timeout must be positive");
    }
  }

  @Override
  public Map<String, String> headers() {
    return Map.copyOf(headers);
  }

  @Override
  public byte[] body() {
    return body.clone();
  }
}
