package io.github.aililuola.mathproofmesh.agent;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Conservative provider-input estimate with explicit tokenizer and message-framing headroom. */
final class InputTokenBudgetEstimator {
  private static final long MESSAGE_FRAMING_TOKENS = 128L;
  private static final long HEADROOM_NUMERATOR = 5L;
  private static final long HEADROOM_DENOMINATOR = 4L;

  private InputTokenBudgetEstimator() {}

  static long estimate(String system, String user) {
    String safeSystem = Objects.requireNonNull(system, "system");
    String safeUser = Objects.requireNonNull(user, "user");
    long characters = Math.addExact(safeSystem.length(), safeUser.length());
    long utf8Bytes =
        Math.addExact(
            safeSystem.getBytes(StandardCharsets.UTF_8).length,
            safeUser.getBytes(StandardCharsets.UTF_8).length);
    long characterEstimate = ceilingDivide(characters, 4L);
    long utf8Estimate = ceilingDivide(utf8Bytes, 3L);
    long contentEstimate = Math.max(characterEstimate, utf8Estimate);
    long withHeadroom =
        ceilingDivide(Math.multiplyExact(contentEstimate, HEADROOM_NUMERATOR), HEADROOM_DENOMINATOR);
    return Math.addExact(withHeadroom, MESSAGE_FRAMING_TOKENS);
  }

  private static long ceilingDivide(long dividend, long divisor) {
    return dividend == 0L ? 0L : 1L + (dividend - 1L) / divisor;
  }
}
