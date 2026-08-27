package io.github.aililuola.mathproofmesh.inspiration;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.DomainOperatorSpec;
import io.github.aililuola.mathproofmesh.contract.SurpriseMutationDirective;
import java.math.BigInteger;
import java.util.List;
import java.util.Set;

/** Seeded, replayable mutation planner over admitted domain operators. */
public final class ControlledMutationPlanner {
  private final DomainOperatorRegistry registry;

  public ControlledMutationPlanner(DomainOperatorRegistry registry) {
    this.registry = java.util.Objects.requireNonNull(registry, "registry");
  }

  public SurpriseMutationDirective plan(
      String problemHash,
      String taskId,
      int proposalSlot,
      String domain,
      String statement,
      List<String> targetObligationIds,
      Set<String> forbiddenOperators) {
    if (proposalSlot < 0) {
      throw new IllegalArgumentException("proposalSlot must be nonnegative");
    }
    List<DomainOperatorSpec> pool =
        registry.applicable(
            domain, statement, "mutation", forbiddenOperators, Integer.MAX_VALUE);
    if (pool.isEmpty()) {
      pool =
          List.of(
              new DomainOperatorSpec(
                  List.of(),
                  "unknown",
                  "mutation",
                  List.of("test the transformed target on a bounded exact domain"),
                  List.of("prove equivalence to the original target"),
                  List.of("mutation may strengthen or reverse a quantifier"),
                  List.of("surprise_exploration"),
                  List.of("target_object"),
                  List.of("dualize"),
                  "generic_dualize",
                  List.of("the transformation is explicitly reversible"),
                  List.of(),
                  List.of("prove both implication directions"),
                  List.of("exact_arithmetic"),
                  "Dualize the current target",
                  "replace the target by an explicitly reversible dual form"));
    }
    String digest = CanonicalJson.stableHash(List.of(problemHash, taskId));
    int baseSeed = new BigInteger(digest.substring(0, 8), 16).intValue() & Integer.MAX_VALUE;
    DomainOperatorSpec selected = pool.get((baseSeed + proposalSlot) % pool.size());
    int seed = baseSeed + proposalSlot;
    String id =
        "mutation_"
            + CanonicalJson.stableHash(
                    List.of(
                        problemHash,
                        taskId,
                        proposalSlot,
                        selected.operatorId(),
                        targetObligationIds))
                .substring(0, 16);
    return new SurpriseMutationDirective(
        true,
        id,
        selected.fastFailureTests(),
        selected.generatedObligations(),
        selected.knownFailureModes(),
        selected.operatorId(),
        selected.preconditions(),
        selected.reversibilityRequirements(),
        seed,
        selected.suggestedTools(),
        targetObligationIds,
        selected.transformation());
  }
}
