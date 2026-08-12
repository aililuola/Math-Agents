package io.github.aililuola.mathproofmesh.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.VerificationVerdict;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class Phase17VerificationLedgerBranchTest {

  @Test
  void restoreRegistrationAndUnknownClaimGuardsAreCovered() {
    ClaimVerificationLedger empty = ClaimVerificationLedger.restore(null);
    var first = empty.register("c1", "a1", null);
    assertThat(empty.register("c1", "other", List.of("ignored"))).isSameAs(first);
    assertThat(first.dependencyIds()).isEmpty();

    ClaimVerificationLedger restored =
        ClaimVerificationLedger.restore(Map.of("c1", first));
    assertThat(restored.snapshot()).containsEntry("c1", first);
    assertThatThrownBy(() -> ClaimVerificationLedger.restore(Map.of("wrong", first)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> restored.get("missing"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void everyVerdictAndMonotonicPromotionBranchIsCovered() {
    ClaimVerificationLedger ledger = new ClaimVerificationLedger();
    ledger.register("pass", "attempt", List.of());
    ledger.register("fail-blank", "attempt", List.of());
    ledger.register("fail-evidence", "attempt", List.of());
    ledger.register("uncertain", "other", List.of());
    ledger.register("skipped", "other", List.of());

    assertThat(
            ledger.applyClaimVerdict(
                "pass", VerificationVerdict.PASS, "review-1", null))
        .satisfies(
            entry -> {
              assertThat(entry.state()).isEqualTo(ClaimVerificationState.INDEPENDENTLY_VERIFIED);
              assertThat(entry.evidenceIds()).containsExactly("review-1");
            });
    assertThat(
            ledger.applyClaimVerdict(
                "pass", VerificationVerdict.PASS, "review-1", null))
        .extracting(ClaimVerificationLedgerEntry::state)
        .isEqualTo(ClaimVerificationState.INDEPENDENTLY_VERIFIED);
    assertThat(
            ledger.applyClaimVerdict(
                "fail-blank", VerificationVerdict.FAIL, " ", " "))
        .extracting(ClaimVerificationLedgerEntry::state)
        .isEqualTo(ClaimVerificationState.REJECTED);
    assertThat(
            ledger.applyClaimVerdict(
                "fail-evidence", VerificationVerdict.FAIL, null, "counterexample"))
        .extracting(ClaimVerificationLedgerEntry::invalidationReason)
        .isEqualTo("claim_level_fail");
    assertThat(
            ledger.applyClaimVerdict(
                "uncertain", VerificationVerdict.UNCERTAIN, "", null))
        .extracting(ClaimVerificationLedgerEntry::state)
        .isEqualTo(ClaimVerificationState.PROPOSED);
    assertThat(
            ledger.applyClaimVerdict(
                    "skipped", VerificationVerdict.SKIPPED, "review-s", null)
                .evidenceIds())
        .contains("review-s");

    assertThatThrownBy(() -> ledger.promoteFactCandidate("uncertain", "referee"))
        .isInstanceOf(IllegalStateException.class);
    assertThat(ledger.promoteFactCandidate("pass", "referee").state())
        .isEqualTo(ClaimVerificationState.FACT_CANDIDATE);
    assertThat(ledger.promoteFactCandidate("pass", null).state())
        .isEqualTo(ClaimVerificationState.FACT_CANDIDATE);
    assertThat(ledger.markFact("pass", List.of("proof", "proof")).state())
        .isEqualTo(ClaimVerificationState.FACT);
    assertThat(ledger.promoteFactCandidate("pass", "late").state())
        .isEqualTo(ClaimVerificationState.FACT);
    assertThat(ledger.markFact("pass", null).evidenceIds())
        .contains("review-1", "referee", "proof", "late");
    assertThatThrownBy(() -> ledger.markFact("uncertain", List.of()))
        .isInstanceOf(IllegalStateException.class);

    ledger.applyAttemptVerdict("attempt", VerificationVerdict.PASS);
    assertThat(ledger.get("pass").sourceAttemptIncomplete()).isFalse();
    ledger.applyAttemptVerdict("attempt", VerificationVerdict.UNCERTAIN);
    assertThat(ledger.get("pass").sourceAttemptIncomplete()).isTrue();
    assertThat(ledger.get("uncertain").sourceAttemptIncomplete()).isFalse();
  }

  @Test
  void invalidationTraversesChainsAndPreservesRejectedAuthority() {
    ClaimVerificationLedger ledger = new ClaimVerificationLedger();
    ledger.register("root", "a", List.of());
    ledger.register("child", "b", List.of("root", "missing"));
    ledger.register("grandchild", "c", List.of("child"));
    ledger.register("independent", "d", List.of());
    ledger.register("already", "e", List.of("root"));
    ledger.applyClaimVerdict("already", VerificationVerdict.FAIL, null, "counterexample");

    assertThat(ledger.invalidate("root", "counterexample", null).state())
        .isEqualTo(ClaimVerificationState.INVALIDATED);
    assertThat(ledger.invalidate("root", "counterexample", List.of("e1", "e1")).evidenceIds())
        .containsExactly("e1");
    assertThat(ledger.invalidateDependents("root", List.of("cascade")))
        .containsExactly("child", "grandchild");
    assertThat(ledger.get("already").state()).isEqualTo(ClaimVerificationState.REJECTED);
    assertThat(ledger.get("independent").state()).isEqualTo(ClaimVerificationState.PROPOSED);
    assertThat(ledger.invalidateDependents("root", null)).isEmpty();

    assertThat(
            ledger.applyClaimVerdict(
                    "root", VerificationVerdict.PASS, "too-late", null)
                .state())
        .isEqualTo(ClaimVerificationState.INVALIDATED);
    assertThat(
            ledger.applyClaimVerdict(
                    "already", VerificationVerdict.PASS, "too-late", null)
                .state())
        .isEqualTo(ClaimVerificationState.REJECTED);
  }
}
