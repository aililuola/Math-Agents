package io.github.aililuola.mathproofmesh.provider;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

public final class ProviderException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final ProviderErrorKind kind;
  private final Integer statusCode;
  private final Duration retryAfter;
  private final boolean retryable;
  private final boolean remoteResultUnknown;
  private final String partialPublicContent;
  private final String partialPublicContentSha256;
  private final int partialReasoningCharacters;

  public ProviderException(
      String safeMessage,
      ProviderErrorKind kind,
      Integer statusCode,
      Duration retryAfter,
      boolean retryable,
      boolean remoteResultUnknown,
      Throwable cause) {
    this(
        safeMessage,
        kind,
        statusCode,
        retryAfter,
        retryable,
        remoteResultUnknown,
        "",
        0,
        cause);
  }

  private ProviderException(
      String safeMessage,
      ProviderErrorKind kind,
      Integer statusCode,
      Duration retryAfter,
      boolean retryable,
      boolean remoteResultUnknown,
      String partialPublicContent,
      int partialReasoningCharacters,
      Throwable cause) {
    super(safeMessage, cause);
    this.kind = java.util.Objects.requireNonNull(kind, "kind");
    this.statusCode = statusCode;
    this.retryAfter = retryAfter;
    this.retryable = retryable;
    this.remoteResultUnknown = remoteResultUnknown;
    this.partialPublicContent =
        partialPublicContent == null ? "" : partialPublicContent;
    this.partialPublicContentSha256 =
        this.partialPublicContent.isEmpty()
            ? null
            : sha256(this.partialPublicContent);
    if (partialReasoningCharacters < 0) {
      throw new IllegalArgumentException(
          "partialReasoningCharacters must not be negative");
    }
    this.partialReasoningCharacters = partialReasoningCharacters;
  }

  public ProviderErrorKind kind() {
    return kind;
  }

  public Integer statusCode() {
    return statusCode;
  }

  public Duration retryAfter() {
    return retryAfter;
  }

  public boolean retryable() {
    return retryable;
  }

  public boolean remoteResultUnknown() {
    return remoteResultUnknown;
  }

  public String partialPublicContent() {
    return partialPublicContent;
  }

  public String partialPublicContentSha256() {
    return partialPublicContentSha256;
  }

  public int partialReasoningCharacters() {
    return partialReasoningCharacters;
  }

  public static ProviderException http(int statusCode, Duration retryAfter) {
    ProviderErrorKind kind;
    boolean retryable;
    if (statusCode == 401 || statusCode == 403) {
      kind = ProviderErrorKind.AUTHENTICATION;
      retryable = false;
    } else if (statusCode == 429) {
      kind = ProviderErrorKind.RATE_LIMIT;
      retryable = true;
    } else if (statusCode == 408 || statusCode == 409 || statusCode >= 500) {
      kind = ProviderErrorKind.RETRYABLE_HTTP;
      retryable = true;
    } else {
      kind = ProviderErrorKind.NON_RETRYABLE_HTTP;
      retryable = false;
    }
    return new ProviderException(
        "provider returned HTTP " + statusCode,
        kind,
        statusCode,
        retryAfter,
        retryable,
        false,
        null);
  }

  public static ProviderException network(Throwable cause) {
    return network(cause, "", 0);
  }

  public static ProviderException network(
      Throwable cause,
      String partialPublicContent,
      int partialReasoningCharacters) {
    return new ProviderException(
        partialReasoningCharacters == 0
            ? "provider transport failed"
            : "provider transport failed after "
                + partialReasoningCharacters
                + " reasoning characters",
        ProviderErrorKind.NETWORK,
        null,
        null,
        true,
        true,
        partialPublicContent,
        partialReasoningCharacters,
        cause);
  }

  public static ProviderException timeout(String phase, Throwable cause) {
    return new ProviderException(
        "provider " + phase + " timeout",
        ProviderErrorKind.TIMEOUT,
        null,
        null,
        true,
        true,
        cause);
  }

  public static ProviderException protocol(String message, Throwable cause) {
    return new ProviderException(
        message,
        ProviderErrorKind.PROTOCOL,
        null,
        null,
        true,
        false,
        cause);
  }

  public static ProviderException tooLarge(long maximumBytes) {
    return new ProviderException(
        "provider response exceeded " + maximumBytes + " bytes",
        ProviderErrorKind.RESPONSE_TOO_LARGE,
        null,
        null,
        false,
        true,
        null);
  }

  public static ProviderException cancelled() {
    return new ProviderException(
        "provider call cancelled",
        ProviderErrorKind.CANCELLED,
        null,
        null,
        false,
        true,
        null);
  }

  public static ProviderException liveCallDisabled() {
    return new ProviderException(
        "live provider calls require explicit opt-in",
        ProviderErrorKind.LIVE_CALL_DISABLED,
        null,
        null,
        false,
        false,
        null);
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required by the JDK", exception);
    }
  }
}
