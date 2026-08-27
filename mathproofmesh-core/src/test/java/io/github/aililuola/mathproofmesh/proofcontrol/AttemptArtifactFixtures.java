package io.github.aililuola.mathproofmesh.proofcontrol;

import io.github.aililuola.mathproofmesh.contract.AttemptStatus;
import io.github.aililuola.mathproofmesh.contract.ClaimCard;
import io.github.aililuola.mathproofmesh.contract.ClaimReviewBatch;
import io.github.aililuola.mathproofmesh.contract.ClaimReviewDecision;
import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.contract.ProofAttempt;
import io.github.aililuola.mathproofmesh.contract.UsageRecord;
import io.github.aililuola.mathproofmesh.contract.VerificationVerdict;
import java.util.List;

final class AttemptArtifactFixtures {
  static final String PROBLEM_HASH = "problem-hash";

  private AttemptArtifactFixtures() {}

  static ClaimCard claim(String id, String statement, List<String> tags) {
    return new ClaimCard(
        List.of(), id, statement, "", "low", List.of(), List.of(), List.of(), List.of(),
        List.of(), 0.9d, "author", "attempt-a", "delta-a", statement,
        ClaimStatus.PROPOSED, tags, null);
  }

  static ProofAttempt attempt(AttemptStatus status, ClaimCard... claims) {
    return new ProofAttempt(
        "author", "attempt-a", List.of(), List.of(), List.of(), List.of(), List.of(), null,
        null, null, PROBLEM_HASH, "bounded attempt", List.of(), List.of(claims),
        "artifact://attempt-a", null, 0, 1, 0.7d, status, "strategy-a", List.of(),
        new UsageRecord());
  }

  static ClaimReviewDecision decision(
      String claimId, VerificationVerdict verdict, boolean witnessChecked) {
    return new ClaimReviewDecision(
        claimId, verdict, verdict == VerificationVerdict.PASS ? 0.95d : 0.8d, List.of(),
        true, true, true, true, witnessChecked, List.of(), verdict.value());
  }

  static ClaimReviewBatch batch(String reportId, ClaimReviewDecision... decisions) {
    return new ClaimReviewBatch(
        reportId, "reviewer", "route-a", "attempt-a", List.of(decisions),
        "artifact://review", new UsageRecord());
  }

  static AttemptArtifactLedger ledger(AttemptStatus attemptStatus, ClaimCard... claims) {
    ProofAttempt attempt = attempt(attemptStatus, claims);
    AttemptArtifactLedger ledger = new AttemptArtifactLedger();
    ledger.addAll(
        new AttemptArtifactHarvester()
            .harvest(PROBLEM_HASH, "route-a", "delta-a", "unverified", attempt,
                java.util.Set.of("obligation-exact")));
    ledger.markReviewPending(attempt.attemptId());
    return ledger;
  }
}
