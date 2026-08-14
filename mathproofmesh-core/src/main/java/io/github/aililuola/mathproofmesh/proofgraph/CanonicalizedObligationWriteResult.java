package io.github.aililuola.mathproofmesh.proofgraph;

import io.github.aililuola.mathproofmesh.contract.ProofObligation;

public record CanonicalizedObligationWriteResult(
    ProofObligation rawObligation,
    ObligationOccurrenceRecord occurrence,
    CanonicalObligationRecord canonicalTarget,
    BottleneckFamilyRecord bottleneckFamily,
    ObligationIdentityStrength identityStrength,
    boolean existingCanonicalTarget,
    boolean possibleEquivalentQuarantined) {

  public CanonicalizedObligationWriteResult {
    rawObligation = java.util.Objects.requireNonNull(rawObligation, "rawObligation");
    occurrence = java.util.Objects.requireNonNull(occurrence, "occurrence");
    canonicalTarget = java.util.Objects.requireNonNull(canonicalTarget, "canonicalTarget");
    identityStrength =
        identityStrength == null ? ObligationIdentityStrength.DISTINCT : identityStrength;
  }
}
