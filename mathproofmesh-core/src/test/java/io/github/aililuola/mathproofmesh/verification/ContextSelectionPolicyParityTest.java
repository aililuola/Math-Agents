package io.github.aililuola.mathproofmesh.verification;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.BlindReviewPacket;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.EvidenceType;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ContextSelectionPolicyParityTest {

  @Test
  void explicit_fact_ids_and_hashes_precede_lexical_similarity() {
    MessageEnvelope explicit =
        VerificationFixtures.fact(
            "explicit-fact",
            "an orthogonal algebraic certificate",
            EvidenceType.NATURAL_PROOF_AUDITED,
            List.of());
    MessageEnvelope dependency =
        VerificationFixtures.fact(
            "dependency-fact",
            "a compact prerequisite",
            EvidenceType.NATURAL_PROOF_AUDITED,
            List.of());
    MessageEnvelope root =
        VerificationFixtures.fact(
            "root-fact",
            "a second orthogonal certificate",
            EvidenceType.NATURAL_PROOF_AUDITED,
            List.of(dependency.messageId()));
    MessageEnvelope lexical =
        VerificationFixtures.fact(
            "lexical-fact",
            "telescoping sum telescoping sum target keyword",
            EvidenceType.NATURAL_PROOF_AUDITED,
            List.of());
    List<MessageEnvelope> messages = List.of(explicit, dependency, root, lexical);
    Set<String> admitted =
        messages.stream()
            .map(MessageEnvelope::messageId)
            .collect(java.util.stream.Collectors.toSet());

    FactContextSelection selection =
        ContextSelectionPolicy.select(
            messages,
            admitted::contains,
            "telescoping sum target keyword",
            50_000,
            3,
            ContextPurpose.FINAL_VERIFICATION,
            List.of(explicit.messageId(), root.contentHash()),
            Map.of());

    assertThat(selection.requiredContextComplete()).isTrue();
    assertThat(selection.selectedMessageIds())
        .containsExactly(
            explicit.messageId(), dependency.messageId(), root.messageId());
    assertThat(selection.selectedMessageIds()).doesNotContain(lexical.messageId());
  }

  @Test
  void context_purpose_changes_fields_and_blind_artifacts_are_path_free() {
    assertThat(ContextPurposePolicy.forPurpose(ContextPurpose.SYNTHESIS).clampChars(12_000, 12_000))
        .isEqualTo(3_600);
    assertThat(ContextPurposePolicy.forPurpose(ContextPurpose.BLIND_REVIEW).clampChars(12_000, 12_000))
        .isEqualTo(5_400);
    MessageEnvelope fact =
        VerificationFixtures.fact(
            "artifact-fact",
            "the exact certificate identity",
            EvidenceType.NATURAL_PROOF_AUDITED,
            List.of());

    ObjectNode synthesis =
        ContextSelectionPolicy.packet(fact, ContextPurpose.SYNTHESIS, null);
    ObjectNode verification =
        ContextSelectionPolicy.packet(fact, ContextPurpose.FINAL_VERIFICATION, null);
    ObjectNode blind =
        ContextSelectionPolicy.packet(
            fact,
            ContextPurpose.BLIND_REVIEW,
            VerificationFixtures.object().put("reviewer_count", 1));

    assertThat(synthesis.has("artifact_refs")).isFalse();
    assertThat(synthesis.has("review_provenance")).isFalse();
    assertThat(verification.path("artifact_refs").size()).isEqualTo(1);
    assertThat(blind.has("artifact_refs")).isFalse();
    assertThat(blind.path("artifact_evidence").size()).isEqualTo(1);
    assertThat(blind.toString())
        .doesNotContain("private-agent")
        .doesNotContain("private-route")
        .doesNotContain("runs/");
  }

  @Test
  void blind_negative_context_is_bounded_but_keeps_counterexamples() {
    List<ObjectNode> negative =
        List.of(
            VerificationFixtures.negative(
                "optional-negative-0",
                "an unrelated discarded generating function",
                "unverified_idea"),
            VerificationFixtures.negative(
                "optional-negative-1",
                "an unrelated analogy",
                "unverified_idea"),
            VerificationFixtures.negative(
                "decisive-counterexample",
                "the claimed parity identity fails at n=2",
                "counterexample"));

    BlindReviewPacket packet =
        new BlindReviewPacketFactory()
            .build(
                VerificationFixtures.problem(),
                "A sanitized proof.",
                List.of(),
                List.of(),
                negative,
                2,
                4_000);

    assertThat(packet.negativeEvidencePackets())
        .extracting(item -> item.path("item_id").asText())
        .containsExactly("decisive-counterexample", "optional-negative-0");
    assertThat(packet.forbiddenClaims())
        .containsExactly("the claimed parity identity fails at n=2");
    assertThat(packet.negativeContextTruncated()).isTrue();
    assertThat(packet.negativeEvidenceOmittedCount()).isEqualTo(1);
  }

  @Test
  void missing_fact_or_omitted_mandatory_negative_fails_closed() {
    BlindReviewPacketFactory factory = new BlindReviewPacketFactory();
    BlindReviewPacket packet =
        factory.build(
            VerificationFixtures.problem(),
            "A sanitized proof.",
            List.of(),
            List.of("unadmitted-explicit-fact"),
            List.of(
                VerificationFixtures.negative(
                    "mandatory-0", "counterexample zero", "counterexample"),
                VerificationFixtures.negative(
                    "mandatory-1", "counterexample one", "counterexample")),
            1,
            4_000);

    assertThat(packet.factContextComplete()).isFalse();
    assertThat(packet.missingCitedFactRefs())
        .containsExactly("unadmitted-explicit-fact");
    assertThat(packet.negativeContextComplete()).isFalse();
    assertThat(packet.negativeMandatoryOmittedCount()).isEqualTo(1);
    assertThat(factory.reviewerPayload(packet).toString())
        .doesNotContain("agent_id")
        .doesNotContain("route_id")
        .doesNotContain("confidence");
    BlindReviewPolicy.assertSafe(factory.reviewerPayload(packet));
  }
}
