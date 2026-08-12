package io.github.aililuola.mathproofmesh.compatibility;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public record LegacyResumeDecision(
    Action action, String checkpointId, int providerCallsBeforeResume, String reason) {
  private static final Set<String> TERMINAL_STATES =
      Set.of("completed", "failed", "cancelled", "canceled", "terminal", "succeeded");

  public enum Action {
    RETURN_TERMINAL,
    RESUME_COMMITTED_CHECKPOINT
  }

  public LegacyResumeDecision {
    action = Objects.requireNonNull(action, "action");
    checkpointId = checkpointId == null ? "" : checkpointId;
    reason = Objects.requireNonNull(reason, "reason");
    if (providerCallsBeforeResume != 0) {
      throw new IllegalArgumentException("resume planning cannot make provider calls");
    }
    if (action == Action.RESUME_COMMITTED_CHECKPOINT && checkpointId.isBlank()) {
      throw new IllegalArgumentException("non-terminal resume requires a committed checkpoint");
    }
  }

  public static LegacyResumeDecision decide(String runStatus, String committedCheckpointId) {
    String normalized =
        Objects.requireNonNullElse(runStatus, "").trim().toLowerCase(Locale.ROOT);
    if (TERMINAL_STATES.contains(normalized)) {
      return new LegacyResumeDecision(
          Action.RETURN_TERMINAL, "", 0, "terminal legacy run is returned without provider work");
    }
    if (committedCheckpointId == null || committedCheckpointId.isBlank()) {
      throw new LegacyImportException(
          "non-terminal legacy run does not identify a committed checkpoint");
    }
    return new LegacyResumeDecision(
        Action.RESUME_COMMITTED_CHECKPOINT,
        committedCheckpointId,
        0,
        "resume continues only from the latest committed checkpoint");
  }
}
