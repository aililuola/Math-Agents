package io.github.aililuola.mathproofmesh.verification;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.VerificationVerdict;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClaimVerificationMonotonicityParityTest {

  @Test
  void incomplete_attempt_does_not_change_child_claim_state() {
    ClaimVerificationLedger ledger = new ClaimVerificationLedger();
    ledger.register("claim-local", "attempt-partial", List.of());

    ledger.applyAttemptVerdict("attempt-partial", VerificationVerdict.FAIL);

    assertThat(ledger.get("claim-local").state())
        .isEqualTo(ClaimVerificationState.PROPOSED);
    assertThat(ledger.get("claim-local").sourceAttemptIncomplete()).isTrue();
  }

  @Test
  void claim_level_counterexample_invalidates_claim() {
    ClaimVerificationLedger ledger = new ClaimVerificationLedger();
    ledger.register("claim-refuted", "attempt-a", List.of());

    ClaimVerificationLedgerEntry entry =
        ledger.applyClaimVerdict(
            "claim-refuted",
            VerificationVerdict.FAIL,
            "claim-report-fail",
            "A typed domain object without property P.");

    assertThat(entry.state()).isEqualTo(ClaimVerificationState.REJECTED);
    assertThat(entry.invalidationReason()).isEqualTo("claim_level_fail");
  }

  @Test
  void dependency_invalidation_demotes_fact() {
    ClaimVerificationLedger ledger = new ClaimVerificationLedger();
    ledger.register("claim-base", "attempt-a", List.of());
    ledger.register("claim-derived", "attempt-a", List.of("claim-base"));
    for (String claim : List.of("claim-base", "claim-derived")) {
      ledger.applyClaimVerdict(
          claim, VerificationVerdict.PASS, "pass-" + claim, null);
      ledger.promoteFactCandidate(claim, "referee-" + claim);
      ledger.markFact(claim, List.of("fact-message-" + claim));
    }

    ledger.invalidate(
        "claim-base", "exact_counterexample", List.of("counterexample-a"));
    List<String> invalidated =
        ledger.invalidateDependents("claim-base", List.of("counterexample-a"));

    assertThat(invalidated).containsExactly("claim-derived");
    assertThat(ledger.get("claim-derived").state())
        .isEqualTo(ClaimVerificationState.INVALIDATED);
  }

  @Test
  void claim_lifecycle_resume_is_monotonic() {
    ClaimVerificationLedger original = new ClaimVerificationLedger();
    original.register("claim-resume", "attempt-resume", List.of());
    original.applyClaimVerdict(
        "claim-resume",
        VerificationVerdict.PASS,
        "independent-pass",
        null);
    ClaimVerificationLedger restored =
        ClaimVerificationLedger.restore(original.snapshot());

    restored.applyAttemptVerdict("attempt-resume", VerificationVerdict.FAIL);

    assertThat(restored.get("claim-resume").state())
        .isEqualTo(ClaimVerificationState.INDEPENDENTLY_VERIFIED);
    assertThat(restored.get("claim-resume").sourceAttemptIncomplete()).isTrue();
  }
}
