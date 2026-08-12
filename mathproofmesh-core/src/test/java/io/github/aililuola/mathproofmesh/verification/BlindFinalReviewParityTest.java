package io.github.aililuola.mathproofmesh.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.BlindReviewPacket;
import io.github.aililuola.mathproofmesh.contract.EvidenceType;
import io.github.aililuola.mathproofmesh.contract.VerificationStage;
import io.github.aililuola.mathproofmesh.contract.VerificationVerdict;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class BlindFinalReviewParityTest {

  @Test
  void final_judge_packets_contain_no_identity_ranking_or_social_metadata() {
    BlindReviewPacketFactory factory = new BlindReviewPacketFactory();
    BlindReviewPacket packet =
        factory.build(
            VerificationFixtures.problem(),
            "Step one proves the difference identity. Step two telescopes it.",
            List.of(
                VerificationFixtures.object()
                    .put("statement", "the finite telescoping identity")
                    .put("content_hash", "abc")
                    .put("agent_id", "hidden-author")
                    .put("ranking", 1)),
            List.of(),
            List.of(),
            2,
            1_000);
    ObjectNode payload = factory.reviewerPayload(packet);

    BlindReviewPolicy.assertSafe(payload);
    assertThat(payload.toString())
        .doesNotContain("hidden-author")
        .doesNotContain("agent_id")
        .doesNotContain("ranking")
        .doesNotContain("confidence")
        .doesNotContain("route_id");
    assertThatThrownBy(
            () ->
                BlindReviewPolicy.assertSafe(
                    VerificationFixtures.object().put("agent_id", "leak")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void blind_detailed_review_receives_no_prior_assessment() {
    AtomicBoolean structuralRan = new AtomicBoolean();
    AtomicBoolean detailedRan = new AtomicBoolean();
    VerificationPipeline.Result result =
        new VerificationPipeline()
            .verify(
                List.of("author"),
                "blind-reviewer",
                () -> {
                  structuralRan.set(true);
                  return VerificationFixtures.report(
                      "blind-reviewer",
                      VerificationStage.STRUCTURAL,
                      VerificationVerdict.PASS);
                },
                () -> {
                  assertThat(structuralRan).isTrue();
                  detailedRan.set(true);
                  return VerificationFixtures.report(
                      "blind-reviewer",
                      VerificationStage.DETAILED,
                      VerificationVerdict.PASS);
                });

    assertThat(result.passed()).isTrue();
    assertThat(detailedRan).isTrue();
    assertThat(result.detailedReport().conciseFeedback())
        .doesNotContain("structural");

    AtomicBoolean blockedDetailed = new AtomicBoolean();
    VerificationPipeline.Result blocked =
        new VerificationPipeline()
            .verify(
                List.of("author"),
                "blind-reviewer",
                () ->
                    VerificationFixtures.report(
                        "blind-reviewer",
                        VerificationStage.STRUCTURAL,
                        VerificationVerdict.FAIL),
                () -> {
                  blockedDetailed.set(true);
                  return VerificationFixtures.report(
                      "blind-reviewer",
                      VerificationStage.DETAILED,
                      VerificationVerdict.PASS);
                });
    assertThat(blocked.detailedExecuted()).isFalse();
    assertThat(blockedDetailed).isFalse();
  }

  @Test
  void blind_packet_includes_typed_fact_and_negative_evidence_without_identity() {
    var fact =
        VerificationFixtures.fact(
            "typed-fact",
            "the exact finite telescoping identity",
            EvidenceType.NATURAL_PROOF_AUDITED,
            List.of());
    ObjectNode factPacket =
        ContextSelectionPolicy.packet(
            fact,
            ContextPurpose.BLIND_REVIEW,
            VerificationFixtures.object()
                .put("independent_referee_recorded", true)
                .put("reviewer_count", 1)
                .put("reviewer_identity_hash", "secret-correlation"));
    BlindReviewPacketFactory factory = new BlindReviewPacketFactory();
    BlindReviewPacket packet =
        factory.build(
            VerificationFixtures.problem(),
            "Apply the exact identity.",
            List.of(factPacket),
            List.of(),
            List.of(
                VerificationFixtures.negative(
                    "typed-negative", "the shortcut fails at n=2", "counterexample")),
            2,
            2_000);
    String serialized = factory.reviewerPayload(packet).toString();

    assertThat(packet.citedFactPackets().getFirst().path("evidence_type").asText())
        .isEqualTo("natural_proof_audited");
    assertThat(packet.negativeEvidencePackets().getFirst().path("evidence_type").asText())
        .isEqualTo("counterexample");
    assertThat(serialized)
        .doesNotContain("private-agent")
        .doesNotContain("private-route")
        .doesNotContain("secret-correlation");
  }
}
