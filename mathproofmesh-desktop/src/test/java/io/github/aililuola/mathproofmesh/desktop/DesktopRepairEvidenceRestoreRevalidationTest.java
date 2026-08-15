package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.EvidenceRef;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimBlindReviewPacketFactory;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimProofRevisionLedger;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimProofRevisionSnapshot;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class DesktopRepairEvidenceRestoreRevalidationTest {
  @Test
  void restoredRevisionCannotReuseEvidenceWhoseAuthorityIsNoLongerAvailable() {
    var claim = DesktopClaimCourtTestFixtures.linearClaim();
    var frozen = DesktopClaimCourtTestFixtures.freeze(claim);
    EvidenceRef evidence =
        new EvidenceRef(
            "artifact://revoked-after-checkpoint",
            "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
            "certificate",
            "Was referenced before restore.");
    ClaimProofRevisionLedger source = new ClaimProofRevisionLedger();
    source.createOriginal(frozen, claim.proofSteps(), List.of(evidence));
    ClaimProofRevisionSnapshot serialized =
        ContractObjectMapper.read(
            ContractObjectMapper.write(source.snapshot()),
            ClaimProofRevisionSnapshot.class);
    ClaimProofRevisionLedger restored = new ClaimProofRevisionLedger();
    restored.restore(serialized);
    var revision = restored.get(frozen.initialProofRevisionId());

    assertThatThrownBy(
            () ->
                new ClaimBlindReviewPacketFactory()
                    .create(frozen, revision, Set.of(), List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("UNKNOWN_EVIDENCE_REF");
  }
}
