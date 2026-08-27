package io.github.aililuola.mathproofmesh.provider;

public enum ProviderErrorKind {
  AUTHENTICATION,
  RATE_LIMIT,
  RETRYABLE_HTTP,
  NON_RETRYABLE_HTTP,
  NETWORK,
  TIMEOUT,
  PROTOCOL,
  RESPONSE_TOO_LARGE,
  CANCELLED,
  LIVE_CALL_DISABLED
}
