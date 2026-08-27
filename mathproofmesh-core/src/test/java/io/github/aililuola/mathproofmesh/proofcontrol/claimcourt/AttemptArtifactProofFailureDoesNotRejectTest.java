package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.AttemptStatus;
import io.github.aililuola.mathproofmesh.contract.ClaimReviewBatch;
import io.github.aililuola.mathproofmesh.contract.ClaimReviewDecision;
import io.github.aililuola.mathproofmesh.contract.UsageRecord;
import io.github.aililuola.mathproofmesh.contract.VerificationVerdict;
import io.github.aililuola.mathproofmesh.proofcontrol.AttemptArtifactKind;
import io.github.aililuola.mathproofmesh.proofcontrol.AttemptArtifactLedger;
import io.github.aililuola.mathproofmesh.proofcontrol.AttemptArtifactRecord;
import io.github.aililuola.mathproofmesh.proofcontrol.AttemptArtifactStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

final class AttemptArtifactProofFailureDoesNotRejectTest {
  @Test
  void legacyReviewFailureProjectsToUncertain() {
    AttemptArtifactLedger ledger = new AttemptArtifactLedger();
    ledger.addAll(
        List.of(
            new AttemptArtifactRecord(
                "artifact-1",
                "problem-hash",
                "route-1",
                "attempt-1",
                AttemptStatus.COMPLETE,
                null,
                "failed",
                AttemptArtifactKind.LOCAL_LEMMA,
                "linear-claim",
                "content-hash",
                "ker(T)={0} implies injectivity",
                "author-agent",
                false,
                null,
                AttemptArtifactStatus.HARVESTED,
                List.of(),
                List.of(),
                null,
                0L,
                List.of())));
    ledger.markReviewPending("attempt-1");
    ledger.applyReviewBatch(
        new ClaimReviewBatch(
            "legacy-review",
            "auditor-agent",
            "route-1",
            "attempt-1",
            List.of(
                new ClaimReviewDecision(
                    "linear-claim",
                    VerificationVerdict.FAIL,
                    1.0d,
                    List.of(),
                    true,
                    true,
                    true,
                    true,
                    false,
                    List.of(),
                    "proof invalid")),
            null,
            new UsageRecord()),
        0.9d);
    assertThat(ledger.get("artifact-1").status()).isEqualTo(AttemptArtifactStatus.UNCERTAIN);
  }
}
