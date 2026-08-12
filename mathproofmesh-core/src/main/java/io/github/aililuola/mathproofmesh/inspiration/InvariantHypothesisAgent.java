package io.github.aililuola.mathproofmesh.inspiration;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.InvariantHypothesis;
import io.github.aililuola.mathproofmesh.contract.NoveltySignature;
import java.util.List;
import java.util.Map;

/** Emits a falsifiable invariant or monovariant hypothesis, never a Fact. */
public final class InvariantHypothesisAgent {
  public InvariantHypothesis propose(
      String problemHash,
      String stateDefinition,
      List<String> operations,
      List<String> targetObligationIds,
      boolean monotone) {
    String expression = monotone ? "potential(state)" : "invariant(state)";
    String behavior = monotone ? "nonincreasing" : "invariant";
    NoveltySignature signature =
        new NoveltySignature(
            List.of("state"),
            List.of(),
            List.of("compare_before_after"),
            List.of("invariant_hypothesis"),
            null,
            null,
            null,
            List.of(monotone ? "monovariant" : "invariant"),
            Map.of(),
            List.of("state"),
            targetObligationIds);
    String id =
        "invariant_"
            + CanonicalJson.stableHash(
                    List.of(problemHash, stateDefinition, operations, behavior))
                .substring(0, 16);
    return new InvariantHypothesis(
        operations,
        behavior,
        "the smallest admissible state",
        "evaluate " + expression + " exactly at the boundary",
        expression,
        "search bounded transitions for a violation of " + behavior,
        id,
        signature,
        stateDefinition,
        targetObligationIds);
  }

  public boolean authoritative(InvariantHypothesis ignored) {
    return false;
  }
}
