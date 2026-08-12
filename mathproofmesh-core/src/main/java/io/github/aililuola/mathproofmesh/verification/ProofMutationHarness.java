package io.github.aililuola.mathproofmesh.verification;

import io.github.aililuola.mathproofmesh.contract.ProofStep;
import java.util.UUID;

/** Deterministic mutation families test both verdict and first-error localization. */
public final class ProofMutationHarness {

  public ProofMutation mutate(ProofStep step, MutationKind kind) {
    java.util.Objects.requireNonNull(step, "step");
    java.util.Objects.requireNonNull(kind, "kind");
    String statement =
        switch (kind) {
          case ALTER_SIGN -> alterSign(step.statement());
          case REVERSE_QUANTIFIER ->
              step.statement().replaceFirst("(?i)for every", "there exists");
          case DROP_ASSUMPTION -> "Without the stated hypotheses, " + step.statement();
          case BREAK_DEPENDENCY ->
              "Independently of its dependencies, " + step.statement();
          case INSERT_CIRCULAR_STEP ->
              "Using this statement itself, conclude " + step.statement();
        };
    return new ProofMutation(
        "mutation-" + UUID.randomUUID(),
        kind,
        step.stepId(),
        statement,
        "fail",
        "synthetic "
            + kind.name().toLowerCase(java.util.Locale.ROOT)
            + " proof fault");
  }

  public void record(
      MutationResult result,
      String domain,
      String role,
      AgentCapabilityProfile profile) {
    profile.update(
        result.agentId(),
        domain,
        role,
        CapabilityObservationKind.MUTATION_BENCHMARK,
        result.detected(),
        null);
    profile.update(
        result.agentId(),
        domain,
        role,
        CapabilityObservationKind.FIRST_ERROR_ACCURACY,
        result.firstErrorCorrect(),
        null);
  }

  private static String alterSign(String statement) {
    if (statement.contains("<=")) {
      return statement.replace("<=", ">=");
    }
    if (statement.contains(">=")) {
      return statement.replace(">=", "<=");
    }
    if (statement.contains("<")) {
      return statement.replace("<", ">");
    }
    if (statement.contains(">")) {
      return statement.replace(">", "<");
    }
    return "Negating the comparison, " + statement;
  }
}
