package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.proofcontrol.ClaimLifecycleController;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ClaimVerifiedRefutationAuthorityTest {
  @Test
  void trustedRefutationCanRejectOpenClaimButWeakAuditCannotDowngradeVerifiedClaim() {
    ClaimLifecycleController lifecycle = new ClaimLifecycleController();
    lifecycle.register("open-claim", "attempt-1", null, List.of(), false);
    assertThat(
            lifecycle
                .recordVerifiedRefutation("open-claim", "counterexample-1", "statement-hash")
                .state())
        .isEqualTo(ClaimLifecycleController.State.REJECTED);

    lifecycle.register("verified-claim", "attempt-2", null, List.of(), false);
    lifecycle.recordLocalVerification("verified-claim", "local-report");
    assertThat(
            lifecycle
                .recordVerifiedRefutation(
                    "verified-claim", "late-counterexample", "statement-hash")
                .state())
        .isEqualTo(ClaimLifecycleController.State.LOCALLY_VERIFIED);
  }
}
