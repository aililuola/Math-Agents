package io.github.aililuola.mathproofmesh.agent;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class ReviewIsolationPolicy {
  private ReviewIsolationPolicy() {}

  public static Set<String> finalReviewAuthorIds(
      List<String> sourceAttemptIds,
      List<AttemptAttribution> attempts,
      List<CheckpointAttribution> checkpoints,
      String synthesizerId) {
    Set<String> winningAttempts =
        Set.copyOf(Objects.requireNonNull(sourceAttemptIds, "sourceAttemptIds"));
    Set<String> winningPaths = new LinkedHashSet<>();
    Set<String> excluded = new LinkedHashSet<>();
    excluded.add(requireText(synthesizerId, "synthesizerId"));
    for (AttemptAttribution attempt :
        Objects.requireNonNull(attempts, "attempts")) {
      if (!winningAttempts.contains(attempt.attemptId())) {
        continue;
      }
      excluded.add(attempt.agentId());
      excluded.addAll(attempt.failoverChain());
      winningPaths.add(attempt.pathId());
    }
    for (CheckpointAttribution checkpoint :
        Objects.requireNonNull(checkpoints, "checkpoints")) {
      if (!winningPaths.contains(checkpoint.pathId())) {
        continue;
      }
      excluded.add(checkpoint.sourceAgentId());
      excluded.addAll(checkpoint.failoverChain());
    }
    return Set.copyOf(excluded);
  }

  public record AttemptAttribution(
      String attemptId,
      String agentId,
      List<String> failoverChain,
      String pathId) {
    public AttemptAttribution {
      attemptId = requireText(attemptId, "attemptId");
      agentId = requireText(agentId, "agentId");
      failoverChain =
          List.copyOf(Objects.requireNonNull(failoverChain, "failoverChain"));
      pathId = requireText(pathId, "pathId");
    }
  }

  public record CheckpointAttribution(
      String pathId, String sourceAgentId, List<String> failoverChain) {
    public CheckpointAttribution {
      pathId = requireText(pathId, "pathId");
      sourceAgentId = requireText(sourceAgentId, "sourceAgentId");
      failoverChain =
          List.copyOf(Objects.requireNonNull(failoverChain, "failoverChain"));
    }
  }

  private static String requireText(String value, String label) {
    Objects.requireNonNull(value, label);
    String normalized = value.strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    return normalized;
  }
}
