package io.github.aililuola.mathproofmesh.computation;

import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import java.util.Locale;
import java.util.Optional;

/** Converts planner prose only into inert suggestions; it never executes a tool. */
public final class ComputationPlanningHints {
  private ComputationPlanningHints() {}

  public static Optional<Hint> infer(String text) {
    String normalized =
        text == null ? "" : text.toLowerCase(Locale.ROOT);
    if (normalized.contains("period") && normalized.contains("finite")) {
      return Optional.of(
          new Hint(
              ComputationMethod.CANDIDATE_PERIOD_CHECK,
              false,
              "A finite period check may falsify the candidate."));
    }
    if (normalized.contains("greedy") && normalized.contains("prefix")) {
      return Optional.of(
          new Hint(
              ComputationMethod.BOUNDED_GREEDY_SEQUENCE,
              false,
              "A typed generator may check one declared finite prefix."));
    }
    return Optional.empty();
  }

  public record Hint(
      ComputationMethod suggestedMethod, boolean broadSearch, String reason) {}
}
