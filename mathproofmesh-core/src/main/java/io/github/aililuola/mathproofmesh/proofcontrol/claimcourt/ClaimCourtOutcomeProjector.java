package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

import io.github.aililuola.mathproofmesh.contract.ClaimCourtOutcome;
import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.proofcontrol.AttemptArtifactStatus;

public final class ClaimCourtOutcomeProjector {
  public record Projection(
      ClaimStatus claimStatus,
      AttemptArtifactStatus attemptArtifactStatus,
      boolean factEligible,
      boolean statementRefuted) {}

  public Projection project(ClaimCourtOutcome outcome) {
    return switch (java.util.Objects.requireNonNull(outcome, "outcome")) {
      case VERIFIED ->
          new Projection(
              ClaimStatus.VERIFIED, AttemptArtifactStatus.VERIFIED_LOCAL, true, false);
      case REFUTED ->
          new Projection(ClaimStatus.REJECTED, AttemptArtifactStatus.REJECTED, false, true);
      case PROOF_INVALID_BUT_CLAIM_OPEN,
          REPAIR_EXHAUSTED,
          INCONCLUSIVE,
          DEFERRED_INDEPENDENCE_UNAVAILABLE ->
          new Projection(ClaimStatus.UNCERTAIN, AttemptArtifactStatus.UNCERTAIN, false, false);
    };
  }
}
