package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

import io.github.aililuola.mathproofmesh.contract.ClaimCard;
import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.contract.ProofStep;
import java.util.List;

public final class ClaimCourtTestFixtures {
  private ClaimCourtTestFixtures() {}

  public static ClaimCard linearClaim() {
    return claim(
        "linear-claim",
        "If a linear map T has ker(T)={0}, then T is injective.",
        "T is injective",
        List.of("T is linear", "ker(T)={0}"),
        List.of(
            step(
                "linear-step",
                "T(x)=T(y) implies x=y.",
                "This follows immediately from linearity.")));
  }

  public static ClaimCard claim(
      String claimId,
      String statement,
      String conclusion,
      List<String> assumptions,
      List<ProofStep> proofSteps) {
    return new ClaimCard(
        assumptions,
        claimId,
        conclusion,
        "",
        "low",
        List.of(),
        List.of(),
        List.of(),
        proofSteps,
        List.of("finite-dimensional setting"),
        0.8d,
        "author-agent",
        "attempt-1",
        null,
        statement,
        ClaimStatus.PROPOSED,
        List.of("local_lemma"),
        null);
  }

  public static ProofStep step(String id, String statement, String justification) {
    return new ProofStep(
        null,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        0.8d,
        List.of(),
        List.of(),
        true,
        justification,
        statement,
        id,
        "derivation");
  }

  public static FrozenClaimSnapshot freeze(ClaimCard claim) {
    return new ClaimFreezeService()
        .freeze(
            "problem-hash",
            "root-goal-hash",
            "route-1",
            claim,
            FrozenClaimSemanticContext.root(claim.scopeLimitations()));
  }

  public static ClaimCourtRolePolicy.Assignment roles() {
    return new ClaimCourtRolePolicy.Assignment(
        "author-agent", "falsifier-agent", "auditor-agent", "repairer-agent", "blind-agent");
  }
}
