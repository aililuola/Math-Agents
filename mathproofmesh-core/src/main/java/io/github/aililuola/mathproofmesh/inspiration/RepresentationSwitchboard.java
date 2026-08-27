package io.github.aililuola.mathproofmesh.inspiration;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.DomainOperatorSpec;
import io.github.aililuola.mathproofmesh.contract.NoveltySignature;
import io.github.aililuola.mathproofmesh.contract.RepresentationCandidate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Produces auditable representation alternatives, never a mechanical rewrite. */
public final class RepresentationSwitchboard {
  private final DomainOperatorRegistry registry;

  public RepresentationSwitchboard(DomainOperatorRegistry registry) {
    this.registry = java.util.Objects.requireNonNull(registry, "registry");
  }

  public List<RepresentationCandidate> propose(
      String problemHash,
      String domain,
      String statement,
      List<String> targetObligationIds,
      Set<String> existingRepresentations,
      int limit) {
    List<RepresentationCandidate> result = new ArrayList<>();
    for (DomainOperatorSpec operator :
        registry.applicable(domain, statement, "representation", Set.of(), limit * 2)) {
      String representation =
          operator.representationTags().isEmpty()
              ? operator.operatorId()
              : operator.representationTags().getFirst();
      if (existingRepresentations != null && existingRepresentations.contains(representation)) {
        continue;
      }
      NoveltySignature signature =
          new NoveltySignature(
              operator.objectTags(),
              List.of(),
              operator.operationTags(),
              List.of("representation_switch"),
              null,
              null,
              null,
              List.of(),
              Map.of(),
              operator.representationTags(),
              targetObligationIds);
      String id =
          "representation_"
              + CanonicalJson.stableHash(
                      List.of(problemHash, operator.operatorId(), targetObligationIds))
                  .substring(0, 16);
      Map<String, String> mapping = new LinkedHashMap<>();
      mapping.put("source_problem", "original mathematical objects");
      mapping.put("target_model", representation + " representation");
      result.add(
          new RepresentationCandidate(
              id,
              operator.title(),
              operator.knownFailureModes(),
              operator.fastFailureTests(),
              operator.generatedObligations(),
              operator.knownFailureModes(),
              List.of(),
              operator.suggestedTools(),
              signature,
              mapping,
              operator.operatorId(),
              operator.preconditions(),
              List.of("the original target and quantified domain"),
              representation,
              operator.reversibilityRequirements(),
              operator.transformation() + ": " + statement,
              problemHash));
      if (result.size() >= limit) {
        break;
      }
    }
    return List.copyOf(result);
  }
}
