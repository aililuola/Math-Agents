package io.github.aililuola.mathproofmesh.proofcontrol.claimcourt;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import org.junit.jupiter.api.Test;

final class ClaimBlindAdjudicationIsolationTest {
  @Test
  void blindPacketContainsNoRoleIdentityOrPriorVerdict() {
    var claim = ClaimCourtTestFixtures.linearClaim();
    FrozenClaimSnapshot frozen = ClaimCourtTestFixtures.freeze(claim);
    ClaimProofRevisionRecord revision = ClaimCourtRepairTestFixtures.original(frozen, claim);
    ClaimBlindReviewPacket packet =
        new ClaimBlindReviewPacketFactory()
            .create(frozen, revision, java.util.Set.of(), java.util.List.of());
    String json = CanonicalJson.canonicalize(packet);
    assertThat(json)
        .doesNotContain("author-agent")
        .doesNotContain("auditor")
        .doesNotContain("repairer")
        .doesNotContain("falsifier")
        .doesNotContain("old_verdict")
        .doesNotContain("repair_hint");
  }
}
