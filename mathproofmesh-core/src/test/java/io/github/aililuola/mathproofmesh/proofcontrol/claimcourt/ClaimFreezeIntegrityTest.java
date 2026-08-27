package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.ClaimCard;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ClaimFreezeIntegrityTest {
  @Test
  void freezesStableSemanticIdentityAndRejectsStatementMutation() {
    ClaimCard original = ClaimCourtTestFixtures.linearClaim();
    ClaimFreezeService service = new ClaimFreezeService();
    FrozenClaimSemanticContext context =
        FrozenClaimSemanticContext.root(original.scopeLimitations());
    FrozenClaimSnapshot first =
        service.freeze("problem-hash", "root-goal-hash", "route-1", original, context);
    FrozenClaimSnapshot second =
        service.freeze("problem-hash", "root-goal-hash", "route-1", original, context);
    assertThat(second).isEqualTo(first);

    ClaimCard changed =
        ClaimCourtTestFixtures.claim(
            original.claimId(),
            "If a linear map T has ker(T)={0}, then T is surjective.",
            "T is surjective",
            original.assumptions(),
            List.copyOf(original.proofSteps()));
    assertThatThrownBy(() -> service.requireUnchanged(first, changed, context))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("FROZEN_CLAIM_MUTATION");
  }
}
