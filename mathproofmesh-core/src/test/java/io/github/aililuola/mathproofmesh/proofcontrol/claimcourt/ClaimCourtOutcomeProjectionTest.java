package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ClaimCourtOutcome;
import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.proofcontrol.AttemptArtifactStatus;
import org.junit.jupiter.api.Test;

final class ClaimCourtOutcomeProjectionTest {
  @Test
  void onlyVerifiedAndRefutedReceiveAuthoritativeTerminalProjections() {
    ClaimCourtOutcomeProjector projector = new ClaimCourtOutcomeProjector();
    assertThat(projector.project(ClaimCourtOutcome.VERIFIED).factEligible()).isTrue();
    assertThat(projector.project(ClaimCourtOutcome.REFUTED).claimStatus())
        .isEqualTo(ClaimStatus.REJECTED);
    for (ClaimCourtOutcome outcome :
        new ClaimCourtOutcome[] {
          ClaimCourtOutcome.PROOF_INVALID_BUT_CLAIM_OPEN,
          ClaimCourtOutcome.REPAIR_EXHAUSTED,
          ClaimCourtOutcome.INCONCLUSIVE,
          ClaimCourtOutcome.DEFERRED_INDEPENDENCE_UNAVAILABLE
        }) {
      assertThat(projector.project(outcome).claimStatus()).isEqualTo(ClaimStatus.UNCERTAIN);
      assertThat(projector.project(outcome).attemptArtifactStatus())
          .isEqualTo(AttemptArtifactStatus.UNCERTAIN);
    }
  }
}
