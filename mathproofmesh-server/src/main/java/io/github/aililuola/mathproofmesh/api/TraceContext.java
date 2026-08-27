package io.github.aililuola.mathproofmesh.api;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

final class TraceContext {
  private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();
  private static final SecureRandom RANDOM = new SecureRandom();
  private static final Pattern TRACE_ID = Pattern.compile("[a-f0-9]{32}");

  private TraceContext() {}

  static Scope bind(String candidate) {
    String prior = CURRENT.get();
    String traceId = fromHeader(candidate);
    CURRENT.set(traceId);
    return new Scope(traceId, prior);
  }

  static String currentOrCreate() {
    String current = CURRENT.get();
    if (current != null) {
      return current;
    }
    return generate();
  }

  static String validate(String traceId) {
    String normalized = traceId == null ? "" : traceId.toLowerCase(Locale.ROOT);
    if (!TRACE_ID.matcher(normalized).matches() || normalized.chars().allMatch(ch -> ch == '0')) {
      throw new IllegalArgumentException("invalid trace identifier");
    }
    return normalized;
  }

  private static String fromHeader(String header) {
    if (header != null) {
      String value = header.trim().toLowerCase(Locale.ROOT);
      if (value.startsWith("00-") && value.length() >= 35) {
        value = value.substring(3, 35);
      }
      if (TRACE_ID.matcher(value).matches() && !value.chars().allMatch(ch -> ch == '0')) {
        return value;
      }
    }
    return generate();
  }

  private static String generate() {
    byte[] bytes = new byte[16];
    do {
      RANDOM.nextBytes(bytes);
    } while (java.util.Arrays.equals(bytes, new byte[16]));
    return HexFormat.of().formatHex(bytes);
  }

  static final class Scope implements AutoCloseable {
    private final String traceId;
    private final String prior;
    private boolean closed;

    private Scope(String traceId, String prior) {
      this.traceId = traceId;
      this.prior = prior;
    }

    String traceId() {
      return traceId;
    }

    @Override
    public void close() {
      if (closed) {
        return;
      }
      if (prior == null) {
        CURRENT.remove();
      } else {
        CURRENT.set(prior);
      }
      closed = true;
    }
  }
}
