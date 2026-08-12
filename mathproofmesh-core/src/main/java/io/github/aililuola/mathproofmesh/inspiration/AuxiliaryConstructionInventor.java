package io.github.aililuola.mathproofmesh.inspiration;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ConstructionProposal;
import io.github.aililuola.mathproofmesh.contract.DomainOperatorSpec;
import io.github.aililuola.mathproofmesh.contract.NoveltySignature;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Constructs a defined auxiliary object plus obligations and a fast falsifier. */
public final class AuxiliaryConstructionInventor {
  private final DomainOperatorRegistry registry;

  public AuxiliaryConstructionInventor(DomainOperatorRegistry registry) {
    this.registry = java.util.Objects.requireNonNull(registry, "registry");
  }

  public ConstructionProposal propose(
      String problemHash,
      String domain,
      String statement,
      List<String> targetObligationIds,
      Set<String> forbiddenOperators) {
    DomainOperatorSpec operator =
        registry
            .applicable(domain, statement, "construction", forbiddenOperators, 1)
            .stream()
            .findFirst()
            .orElseGet(
                () ->
                    new DomainOperatorSpec(
                        List.of(),
                        domain == null || domain.isBlank() ? "unknown" : domain,
                        "construction",
                        List.of("search a bounded family for an immediate contradiction"),
                        List.of("prove the construction is well-defined"),
                        List.of("the construction may be degenerate"),
                        List.of("auxiliary_construction"),
                        List.of("auxiliary_object"),
                        List.of("construct"),
                        "generic_auxiliary_object",
                        List.of("the target obligation is open"),
                        List.of(),
                        List.of("prove the construction preserves the target"),
                        List.of("bounded_search"),
                        "Define a target-specific auxiliary object",
                        "introduce an object tied to the first open gap"));
    NoveltySignature signature =
        new NoveltySignature(
            operator.objectTags(),
            List.of(),
            operator.operationTags(),
            List.of("auxiliary_construction"),
            null,
            null,
            null,
            List.of(),
            Map.of(),
            operator.representationTags(),
            targetObligationIds);
    String id =
        "construction_"
            + CanonicalJson.stableHash(
                    List.of(problemHash, operator.operatorId(), targetObligationIds))
                .substring(0, 16);
    return new ConstructionProposal(
        operator.objectTags().isEmpty() ? List.of("auxiliary_object") : operator.objectTags(),
        operator.family(),
        operator.transformation(),
        "the constructed object exposes a relation that can close the target gap",
        "replace one opaque gap with explicit preservation and terminal obligations",
        operator.knownFailureModes(),
        operator.fastFailureTests(),
        operator.generatedObligations(),
        targetObligationIds,
        signature,
        operator.operatorId(),
        operator.preconditions(),
        id,
        operator.reversibilityRequirements(),
        operator.suggestedTools());
  }
}
