package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

final class ClaimCourtSnapshotTest {
  @Test
  void restorePreservesFrozenCaseAndStableHash() {
    FrozenClaimSnapshot frozen = ClaimCourtTestFixtures.freeze(ClaimCourtTestFixtures.linearClaim());
    ClaimCourtLedger ledger = new ClaimCourtLedger();
    ledger.open(frozen, ClaimCourtTestFixtures.roles());
    String hash = ledger.stableHash();
    ClaimCourtLedger restored = new ClaimCourtLedger();
    restored.restore(ledger.snapshot());
    assertThat(restored.stableHash()).isEqualTo(hash);
    assertThat(restored.get(frozen.courtCaseId()).frozenClaim()).isEqualTo(frozen);
  }
}
