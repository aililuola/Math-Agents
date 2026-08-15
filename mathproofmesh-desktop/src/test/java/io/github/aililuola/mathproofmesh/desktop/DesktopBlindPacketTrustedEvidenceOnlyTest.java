package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.EvidenceRef;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimBlindReviewPacketFactory;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimProofRevisionLedger;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.ClaimTrustedEvidenceAuthority;
import io.github.aililuola.mathproofmesh.proofcontrol.claimcourt.TrustedClaimEvidence;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class DesktopBlindPacketTrustedEvidenceOnlyTest {
  @Test
  void blindPacketRevalidatesEveryEvidenceReference() {
    var claim = DesktopClaimCourtTestFixtures.linearClaim();
    var frozen = DesktopClaimCourtTestFixtures.freeze(claim);
    EvidenceRef supplied =
        new EvidenceRef(
            "artifact://repair-evidence",
            "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
            "certificate",
            "Repairer-provided reference.");
    var revision =
        new ClaimProofRevisionLedger()
            .createOriginal(frozen, claim.proofSteps(), List.of(supplied));
    ClaimBlindReviewPacketFactory factory = new ClaimBlindReviewPacketFactory();

    assertThatThrownBy(
            () -> factory.create(frozen, revision, Set.of(), List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("UNKNOWN_EVIDENCE_REF");

    EvidenceRef canonical =
        new EvidenceRef(
            supplied.artifactRef(),
            supplied.contentHash(),
            supplied.section(),
            "Server-resolved evidence.");
    var packet =
        factory.create(
            frozen,
            revision,
            Set.of(),
            List.of(
                new TrustedClaimEvidence(
                    "trusted-repair-evidence",
                    canonical,
                    frozen.problemHash(),
                    frozen.claimSemanticHash(),
                    ClaimTrustedEvidenceAuthority.FORMAL_CERTIFICATE,
                    true,
                    true,
                    true,
                    true)));

    assertThat(packet.trustedEvidenceRefs()).containsExactly(canonical);
    assertThat(packet.trustedEvidenceRefs()).doesNotContain(supplied);
  }
}
