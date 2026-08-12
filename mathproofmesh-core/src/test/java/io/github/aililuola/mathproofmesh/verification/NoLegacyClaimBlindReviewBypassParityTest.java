package io.github.aililuola.mathproofmesh.verification;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.BlindReviewPacket;
import java.util.List;
import org.junit.jupiter.api.Test;

class NoLegacyClaimBlindReviewBypassParityTest {
  private static final String MARKER =
      "REJECTED_ROUTE_LOCAL_CLAIM_DO_NOT_SHARE";

  @Test
  void hierarchical_blind_packet_quarantines_legacy_claims() {
    ObjectNode legacy =
        VerificationFixtures.object()
            .put("statement", MARKER)
            .put("content_hash", "legacy");
    ObjectNode typed =
        VerificationFixtures.object()
            .put("statement", "admitted typed Fact")
            .put("content_hash", "typed");
    BlindReviewPacketFactory factory = new BlindReviewPacketFactory();

    BlindReviewPacket hierarchical =
        factory.build(
            VerificationFixtures.problem(),
            "A sanitized proof.",
            LegacyClaimQuarantine.admissible(
                "hierarchical_sparse", List.of(typed), List.of(legacy)),
            List.of(),
            List.of(),
            0,
            0);
    BlindReviewPacket legacyPacket =
        factory.build(
            VerificationFixtures.problem(),
            "A sanitized proof.",
            LegacyClaimQuarantine.admissible(
                "legacy_sparse", List.of(typed), List.of(legacy)),
            List.of(),
            List.of(),
            0,
            0);

    assertThat(factory.reviewerPayload(hierarchical).toString())
        .doesNotContain(MARKER);
    assertThat(factory.reviewerPayload(legacyPacket).toString())
        .contains(MARKER);
  }
}
