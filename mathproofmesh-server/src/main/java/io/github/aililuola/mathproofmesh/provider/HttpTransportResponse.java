package io.github.aililuola.mathproofmesh.provider;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class HttpTransportResponse implements AutoCloseable {
  private final int statusCode;
  private final Map<String, List<String>> headers;
  private final InputStream body;

  public HttpTransportResponse(
      int statusCode, Map<String, List<String>> headers, InputStream body) {
    if (statusCode < 100 || statusCode > 999) {
      throw new IllegalArgumentException("statusCode is outside the HTTP range");
    }
    this.statusCode = statusCode;
    this.headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
    this.body = Objects.requireNonNull(body, "body");
  }

  public int statusCode() {
    return statusCode;
  }

  public Map<String, List<String>> headers() {
    return headers;
  }

  public String firstHeader(String name) {
    Objects.requireNonNull(name, "name");
    return headers.entrySet().stream()
        .filter(entry -> asciiEqualsIgnoreCase(entry.getKey(), name))
        .flatMap(entry -> entry.getValue().stream())
        .findFirst()
        .orElse(null);
  }

  private static boolean asciiEqualsIgnoreCase(String left, String right) {
    if (left.length() != right.length()) {
      return false;
    }
    for (int index = 0; index < left.length(); index++) {
      char leftCharacter = asciiLower(left.charAt(index));
      char rightCharacter = asciiLower(right.charAt(index));
      if (leftCharacter != rightCharacter) {
        return false;
      }
    }
    return true;
  }

  private static char asciiLower(char character) {
    if (character >= 'A' && character <= 'Z') {
      return (char) (character + ('a' - 'A'));
    }
    return character;
  }

  public InputStream body() {
    return body;
  }

  @Override
  public void close() throws IOException {
    body.close();
  }
}
