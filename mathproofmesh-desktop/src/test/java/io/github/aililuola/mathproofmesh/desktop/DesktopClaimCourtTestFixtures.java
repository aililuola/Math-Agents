package io.github.aililuola.mathproofmesh.desktop;

import io.github.aililuola.mathproofmesh.contract.ClaimCard;
import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.contract.ProofStep;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimFreezeService;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.FrozenClaimSemanticContext;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.FrozenClaimSnapshot;
import java.util.List;

final class DesktopClaimCourtTestFixtures {
  private DesktopClaimCourtTestFixtures() {}

  static ClaimCard linearClaim() {
    return new ClaimCard(
        List.of("T is linear", "ker(T)={0}"),
        "linear-claim",
        "T is injective",
        "",
        "low",
        List.of(),
        List.of(),
        List.of(),
        List.of(
            new ProofStep(
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                0.8d,
                List.of(),
                List.of(),
                true,
                "This follows immediately from linearity.",
                "T(x)=T(y) implies x=y.",
                "linear-step",
                "derivation")),
        List.of("finite-dimensional setting"),
        0.8d,
        "author-agent",
        "attempt-1",
        null,
        "If a linear map T has ker(T)={0}, then T is injective.",
        ClaimStatus.PROPOSED,
        List.of("local_lemma"),
        null);
  }

  static FrozenClaimSnapshot freeze(ClaimCard claim) {
    return new ClaimFreezeService()
        .freeze(
            "problem-hash",
            "root-goal-hash",
            "route-1",
            claim,
            FrozenClaimSemanticContext.root(claim.scopeLimitations()));
  }
}
