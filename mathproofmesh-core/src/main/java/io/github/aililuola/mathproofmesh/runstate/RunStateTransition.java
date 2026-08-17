package io.github.aililuola.mathproofmesh.runstate;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record RunStateTransition(
    String transitionId,
    String runId,
    long sequence,
    String fromStateHash,
    String toStateHash,
    RunStateTransitionTrigger trigger,
    Map<String, String> payload,
    Instant createdAt) {
  public RunStateTransition {
    transitionId = RunStateHashes.required(transitionId, "transitionId");
    runId = RunStateHashes.required(runId, "runId");
    fromStateHash = RunStateHashes.optional(fromStateHash);
    toStateHash = RunStateHashes.required(toStateHash, "toStateHash");
    trigger = Objects.requireNonNull(trigger, "trigger");
    payload = payload == null ? Map.of() : Map.copyOf(payload);
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    if (sequence < 0L) {
      throw new IllegalArgumentException("transition sequence must not be negative");
    }
  }

  @Override
  public Map<String, String> payload() {
    return Map.copyOf(payload);
  }
}
