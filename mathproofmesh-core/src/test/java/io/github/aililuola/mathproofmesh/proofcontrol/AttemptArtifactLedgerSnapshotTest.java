package io.github.aililuola.mathproofmesh.proofcontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.AttemptStatus;
import io.github.aililuola.mathproofmesh.contract.ClaimReviewBatch;
import io.github.aililuola.mathproofmesh.contract.ClaimReviewDecision;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.UsageRecord;
import io.github.aililuola.mathproofmesh.contract.VerificationVerdict;
import java.util.List;
import org.junit.jupiter.api.Test;

final class AttemptArtifactLedgerSnapshotTest {

  @Test
  void snapshotRoundTripPreservesReviewIdentityAndMonotonicAuthority() {
    var claim = AttemptArtifactFixtures.claim("local", "A reusable local result.", List.of());
    AttemptArtifactLedger ledger = AttemptArtifactFixtures.ledger(AttemptStatus.FAILED, claim);
    var review =
        AttemptArtifactFixtures.batch(
            "review-a", AttemptArtifactFixtures.decision("local", VerificationVerdict.PASS, false));
    AttemptArtifactRecord verified = ledger.applyReviewBatch(review, 0.8d).getFirst();
    ledger.markPromoted(verified.artifactId(), "fact-local");
    String beforeHash = ledger.ledgerHash();

    AttemptArtifactSnapshot decoded =
        ContractObjectMapper.read(
            ContractObjectMapper.write(ledger.snapshot()), AttemptArtifactSnapshot.class);
    AttemptArtifactLedger restored = AttemptArtifactLedger.restore(decoded);

    assertThat(restored.ledgerHash()).isEqualTo(beforeHash);
    assertThat(restored.reviewed("attempt-a")).isTrue();
    assertThat(restored.records()).singleElement().satisfies(record -> {
      assertThat(record.status()).isEqualTo(AttemptArtifactStatus.PROMOTED_FACT);
      assertThat(record.promotedMessageId()).isEqualTo("fact-local");
    });
    assertThat(restored.applyReviewBatch(review, 0.8d)).hasSize(1);
    assertThatThrownBy(
            () -> restored.applyReviewBatch(
                AttemptArtifactFixtures.batch(
                    "later-review",
                    AttemptArtifactFixtures.decision("local", VerificationVerdict.FAIL, false)),
                0.8d))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void reviewBatchKeepsMissingFailedAndWeakClaimsDistinct() {
    var missing = AttemptArtifactFixtures.claim("missing", "A missing decision.", List.of());
    var failed = AttemptArtifactFixtures.claim("failed", "A claim with an invalid proof.", List.of());
    var weak = AttemptArtifactFixtures.claim("weak", "A weakly supported claim.", List.of());
    AttemptArtifactLedger ledger =
        AttemptArtifactFixtures.ledger(AttemptStatus.FAILED, missing, failed, weak);
    ClaimReviewDecision weakDecision =
        new ClaimReviewDecision(
            "weak",
            VerificationVerdict.PASS,
            0.5d,
            List.of(),
            true,
            true,
            true,
            true,
            false,
            List.of(),
            "below the promotion threshold");

    List<AttemptArtifactRecord> reviewed =
        ledger.applyReviewBatch(
            AttemptArtifactFixtures.batch(
                "review-outcomes",
                AttemptArtifactFixtures.decision("failed", VerificationVerdict.FAIL, false),
                weakDecision),
            0.8d);

    assertThat(reviewed)
        .extracting(AttemptArtifactRecord::claimId, AttemptArtifactRecord::status)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple("missing", AttemptArtifactStatus.UNCERTAIN),
            org.assertj.core.groups.Tuple.tuple("failed", AttemptArtifactStatus.UNCERTAIN),
            org.assertj.core.groups.Tuple.tuple("weak", AttemptArtifactStatus.UNCERTAIN));
    assertThat(reviewed).noneMatch(record -> record.status() == AttemptArtifactStatus.REJECTED);
    assertThat(reviewed).allSatisfy(record -> assertThat(record.terminal()).isTrue());
  }

  @Test
  void counterexampleRequiresItsDedicatedTerminalTransition() {
    var counterexample =
        AttemptArtifactFixtures.claim(
            "counter",
            "A checked witness refutes the exact obligation.",
            List.of("artifact:counterexample", "counterexample-target:obligation-exact"));
    AttemptArtifactLedger ledger =
        AttemptArtifactFixtures.ledger(AttemptStatus.FAILED, counterexample);
    AttemptArtifactRecord verified =
        ledger
            .applyReviewBatch(
                AttemptArtifactFixtures.batch(
                    "review-counter",
                    AttemptArtifactFixtures.decision(
                        "counter", VerificationVerdict.PASS, true)),
                0.8d)
            .getFirst();

    assertThatThrownBy(() -> ledger.markPromoted(verified.artifactId(), "fact-counter"))
        .isInstanceOf(IllegalArgumentException.class);
    AttemptArtifactRecord applied =
        ledger.markCounterexampleApplied(verified.artifactId(), "fact-counter");

    assertThat(applied.status()).isEqualTo(AttemptArtifactStatus.APPLIED_COUNTEREXAMPLE);
    assertThat(applied.terminal()).isTrue();
    assertThat(AttemptArtifactLedger.restore(null).records()).isEmpty();
    assertThat(new AttemptArtifactSnapshot(null, null).records()).isEmpty();
    assertThat(new ClaimLifecycleSnapshot(null).entries()).isEmpty();
  }

  @Test
  void reviewAuthorityBoundariesRejectInvalidBatchesBeforeMutation() {
    var claim = AttemptArtifactFixtures.claim("local", "A bounded local claim.", List.of());
    AttemptArtifactLedger ledger = AttemptArtifactFixtures.ledger(AttemptStatus.FAILED, claim);

    assertThatThrownBy(
            () ->
                ledger.applyReviewBatch(
                    AttemptArtifactFixtures.batch(
                        "invalid-threshold",
                        AttemptArtifactFixtures.decision(
                            "local", VerificationVerdict.PASS, false)),
                    Double.NaN))
        .isInstanceOf(IllegalArgumentException.class);
    ClaimReviewBatch authorReview =
        new ClaimReviewBatch(
            "author-review",
            "author",
            "route-a",
            "attempt-a",
            List.of(
                AttemptArtifactFixtures.decision(
                    "local", VerificationVerdict.PASS, false)),
            "artifact://author-review",
            new UsageRecord());
    assertThatThrownBy(() -> ledger.applyReviewBatch(authorReview, 0.8d))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(ledger.get(ledger.records().getFirst().artifactId()).status())
        .isEqualTo(AttemptArtifactStatus.REVIEW_PENDING);
  }
}
