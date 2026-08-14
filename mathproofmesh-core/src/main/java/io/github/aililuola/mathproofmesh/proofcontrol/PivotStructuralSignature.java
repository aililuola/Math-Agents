package io.github.aililuola.mathproofmesh.proofcontrol;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Semantic projection used to prove that a proposed pivot changes mathematical state. */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "The compact constructor replaces every set with an immutable copy.")
public record PivotStructuralSignature(
    String strategyId,
    Set<String> activeObjectIds,
    Set<String> activeCanonicalTargetIds,
    Set<String> activeAssumptionHashes,
    Set<String> retainedVerifiedClaimIds,
    Set<String> proposedClaimHashes,
    Set<String> activeObligationSignatures,
    String directionSignature,
    String blueprintStructureHash) {
  public PivotStructuralSignature {
    strategyId = PivotValues.required(strategyId, "strategyId");
    activeObjectIds = immutable(activeObjectIds);
    activeCanonicalTargetIds = immutable(activeCanonicalTargetIds);
    activeAssumptionHashes = immutable(activeAssumptionHashes);
    retainedVerifiedClaimIds = immutable(retainedVerifiedClaimIds);
    proposedClaimHashes = immutable(proposedClaimHashes);
    activeObligationSignatures = immutable(activeObligationSignatures);
    directionSignature = PivotValues.required(directionSignature, "directionSignature");
    blueprintStructureHash =
        PivotValues.required(blueprintStructureHash, "blueprintStructureHash");
  }

  public String semanticHash() {
    return CanonicalJson.stableHash(
        Map.of(
            "active_objects", activeObjectIds,
            "active_targets", activeCanonicalTargetIds,
            "assumptions", activeAssumptionHashes,
            "retained_claims", retainedVerifiedClaimIds,
            "proposed_claims", proposedClaimHashes,
            "obligations", activeObligationSignatures,
            "direction", ProofIdentity.normalizeText(directionSignature),
            "blueprint", blueprintStructureHash));
  }

  public boolean sameSemanticState(PivotStructuralSignature other) {
    return other != null && semanticHash().equals(other.semanticHash());
  }

  private static Set<String> immutable(Set<String> values) {
    if (values == null) {
      return Set.of();
    }
    LinkedHashSet<String> normalized = new LinkedHashSet<>();
    values.stream()
        .map(value -> PivotValues.required(value, "signature value"))
        .sorted()
        .forEach(normalized::add);
    return Set.copyOf(normalized);
  }
}
