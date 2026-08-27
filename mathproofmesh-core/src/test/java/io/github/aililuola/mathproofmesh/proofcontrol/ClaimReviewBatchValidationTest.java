package io.github.aililuola.mathproofmesh.proofcontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.AttemptStatus;
import io.github.aililuola.mathproofmesh.contract.ClaimReviewBatch;
import io.github.aililuola.mathproofmesh.contract.ContractValidationException;
import io.github.aililuola.mathproofmesh.contract.UsageRecord;
import io.github.aililuola.mathproofmesh.contract.VerificationVerdict;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ClaimReviewBatchValidationTest {

  @Test
  void duplicateAndExtraDecisionsRejectTheWholeBatchWhileMissingIsUncertain() {
    var first = AttemptArtifactFixtures.claim("first", "First claim.", List.of());
    var second = AttemptArtifactFixtures.claim("second", "Second claim.", List.of());
    AttemptArtifactLedger ledger = AttemptArtifactFixtures.ledger(AttemptStatus.FAILED, first, second);

    assertThatThrownBy(
            () -> AttemptArtifactFixtures.batch(
                "duplicate", AttemptArtifactFixtures.decision("first", VerificationVerdict.PASS, false),
                AttemptArtifactFixtures.decision("first", VerificationVerdict.FAIL, false)))
        .isInstanceOf(ContractValidationException.class);

    ClaimReviewBatch extra =
        AttemptArtifactFixtures.batch(
            "extra", AttemptArtifactFixtures.decision("not-a-candidate", VerificationVerdict.PASS, false));
    assertThatThrownBy(() -> ledger.applyReviewBatch(extra, 0.8d))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(ledger.records()).extracting(AttemptArtifactRecord::status)
        .containsOnly(AttemptArtifactStatus.REVIEW_PENDING);

    ClaimReviewBatch bounded =
        AttemptArtifactFixtures.batch(
            "bounded", AttemptArtifactFixtures.decision("first", VerificationVerdict.PASS, false));
    ledger.applyReviewBatch(bounded, 0.8d);
    assertThat(ledger.records()).anySatisfy(
        record -> {
          if (record.claimId().equals("first")) {
            assertThat(record.status()).isEqualTo(AttemptArtifactStatus.VERIFIED_LOCAL);
          } else {
            assertThat(record.status()).isEqualTo(AttemptArtifactStatus.UNCERTAIN);
          }
        });

    assertThatThrownBy(
            () -> ledger.applyReviewBatch(
                new ClaimReviewBatch(
                    "second-batch", "other-reviewer", "route-a", "attempt-a", List.of(), null,
                    new UsageRecord()), 0.8d))
        .isInstanceOf(IllegalStateException.class);
  }
}
