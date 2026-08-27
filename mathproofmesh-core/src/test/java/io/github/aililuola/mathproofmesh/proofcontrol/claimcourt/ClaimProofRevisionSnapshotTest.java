package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

final class ClaimProofRevisionSnapshotTest {
  @Test
  void snapshotRestorePreservesRevisionHashAndExactlyOnceIdentity() {
    var claim = ClaimCourtTestFixtures.linearClaim();
    FrozenClaimSnapshot frozen = ClaimCourtTestFixtures.freeze(claim);
    ClaimProofRevisionLedger ledger = new ClaimProofRevisionLedger();
    ledger.createOriginal(frozen, claim.proofSteps(), claim.evidenceRefs());
    String hash = ledger.stableHash();
    ClaimProofRevisionLedger restored = new ClaimProofRevisionLedger();
    restored.restore(ledger.snapshot());
    assertThat(restored.stableHash()).isEqualTo(hash);
    assertThat(restored.records()).hasSize(1);
  }
}
