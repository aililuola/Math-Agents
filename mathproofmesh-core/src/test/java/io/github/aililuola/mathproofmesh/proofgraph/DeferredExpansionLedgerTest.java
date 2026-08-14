package io.github.aililuola.mathproofmesh.proofgraph;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DeferredExpansionLedgerTest {
  @Test
  void snapshotsAndRestoresDeferralsExactlyOnce() {
    DeferredExpansionLedger ledger = new DeferredExpansionLedger();
    FocusedExpansionDecision decision = FocusedExpansionDecision.deferCapacity();
    ledger.record(
        ObligationCanonicalizationTestFixtures.PROBLEM_HASH,
        3,
        "route-a",
        "obligation-a",
        "",
        FocusedRecoveryActionType.NEW_STRATEGY,
        decision);
    ledger.record(
        ObligationCanonicalizationTestFixtures.PROBLEM_HASH,
        3,
        "route-a",
        "obligation-a",
        "",
        FocusedRecoveryActionType.NEW_STRATEGY,
        decision);

    String hash = ledger.stableHash();
    DeferredExpansionLedger restored = DeferredExpansionLedger.restore(ledger.snapshot());

    assertThat(ledger.records()).hasSize(1);
    assertThat(restored.records()).containsExactlyElementsOf(ledger.records());
    assertThat(restored.stableHash()).isEqualTo(hash);
  }
}
