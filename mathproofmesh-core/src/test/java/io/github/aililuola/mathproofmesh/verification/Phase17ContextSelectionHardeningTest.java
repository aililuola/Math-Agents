package io.github.aililuola.mathproofmesh.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.EvidenceType;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class Phase17ContextSelectionHardeningTest {

  @Test
  void evidencePrioritiesAndPacketPoliciesCoverEveryVariant() {
    List<Double> priorities =
        Arrays.stream(EvidenceType.values())
            .map(ContextSelectionPolicy::evidencePriority)
            .toList();
    assertThat(priorities).hasSize(EvidenceType.values().length);
    assertThat(priorities.getFirst()).isEqualTo(0.05d);
    assertThat(priorities.getLast()).isEqualTo(1.0d);
    assertThatThrownBy(() -> ContextSelectionPolicy.evidencePriority(null))
        .isInstanceOf(NullPointerException.class);

    ObjectNode provenance = VerificationFixtures.object().put("reviewer", "blind");
    for (EvidenceType type : EvidenceType.values()) {
      MessageEnvelope message =
          with(
              VerificationFixtures.fact("packet-" + type.value(), "statement", type, List.of()),
              List.of(),
              List.of(),
              List.of("artifact://certificate"),
              List.of("r1", "r2", "r3", "r4", "r5"));
      ObjectNode synthesis =
          ContextSelectionPolicy.packet(message, ContextPurpose.SYNTHESIS, provenance);
      ObjectNode finalPacket =
          ContextSelectionPolicy.packet(message, ContextPurpose.FINAL_VERIFICATION, provenance);
      ObjectNode blind =
          ContextSelectionPolicy.packet(message, ContextPurpose.BLIND_REVIEW, provenance);
      assertThat(synthesis.has("normalization_confidence")).isFalse();
      assertThat(finalPacket.has("normalization_confidence")).isTrue();
      assertThat(finalPacket.has("review_provenance")).isTrue();
      assertThat(finalPacket.has("artifact_refs")).isTrue();
      assertThat(blind.has("artifact_refs")).isFalse();
      assertThat(blind.path("artifact_evidence")).hasSize(1);
      assertThat(blind.path("artifact_evidence").get(0).path("replay_status").asText())
          .isIn("available_not_replayed_in_packet", "not_applicable");
    }
    MessageEnvelope noArtifacts =
        with(
            VerificationFixtures.fact(
                "no-artifacts", "statement", EvidenceType.UNVERIFIED_IDEA, List.of()),
            List.of(),
            List.of(),
            List.of(),
            List.of());
    assertThat(ContextSelectionPolicy.packet(noArtifacts, ContextPurpose.BLIND_REVIEW, null)
            .has("artifact_evidence"))
        .isFalse();
  }

  @Test
  void selectionFailsClosedForInvalidBudgetsMissingRefsCyclesAndOversizedClosures() {
    MessageEnvelope base =
        VerificationFixtures.fact(
            "base", "alpha prerequisite", EvidenceType.NATURAL_PROOF_AUDITED, List.of());
    MessageEnvelope root =
        VerificationFixtures.fact(
            "root", "beta conclusion", EvidenceType.NATURAL_PROOF_AUDITED, List.of("base"));
    List<MessageEnvelope> messages = List.of(base, root);
    Set<String> admitted = Set.of("base", "root");

    assertThatThrownBy(
            () ->
                ContextSelectionPolicy.select(
                    null,
                    admitted::contains,
                    "query",
                    100,
                    1,
                    ContextPurpose.SYNTHESIS,
                    List.of(),
                    null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                ContextSelectionPolicy.select(
                    messages,
                    null,
                    "query",
                    100,
                    1,
                    ContextPurpose.SYNTHESIS,
                    List.of(),
                    null))
        .isInstanceOf(NullPointerException.class);

    for (int[] limits : List.of(new int[] {0, 2}, new int[] {100, 0}, new int[] {-1, -1})) {
      var selection =
          ContextSelectionPolicy.select(
              messages,
              admitted::contains,
              "query",
              limits[0],
              limits[1],
              ContextPurpose.SYNTHESIS,
              Arrays.asList(" missing ", null, "", "external:oracle", "base"),
              null);
      assertThat(selection.selectedMessageIds()).isEmpty();
      assertThat(selection.missingRequiredRefs()).contains("missing");
      assertThat(selection.maxChars()).isGreaterThanOrEqualTo(0);
      assertThat(selection.truncated()).isTrue();
    }
    assertThat(
            ContextSelectionPolicy.select(
                    messages,
                    ignored -> false,
                    "query",
                    100,
                    2,
                    ContextPurpose.SYNTHESIS,
                    List.of("base"),
                    null)
                .truncated())
        .isFalse();

    var tooFew =
        ContextSelectionPolicy.select(
            messages,
            admitted::contains,
            "beta",
            100_000,
            1,
            ContextPurpose.FINAL_VERIFICATION,
            List.of("root"),
            null);
    assertThat(tooFew.requiredContextComplete()).isFalse();
    assertThat(tooFew.missingRequiredRefs()).contains("root");

    var tooSmall =
        ContextSelectionPolicy.select(
            messages,
            admitted::contains,
            "beta",
            1,
            5,
            ContextPurpose.FINAL_VERIFICATION,
            List.of("root"),
            null);
    assertThat(tooSmall.missingRequiredRefs()).contains("root");

    MessageEnvelope missing =
        VerificationFixtures.fact(
            "missing-dependency",
            "gamma",
            EvidenceType.NATURAL_PROOF_AUDITED,
            List.of("not-present", "external:allowed"));
    var incomplete =
        ContextSelectionPolicy.select(
            List.of(missing),
            ignored -> true,
            "gamma",
            100_000,
            5,
            ContextPurpose.SYNTHESIS,
            List.of("missing-dependency"),
            null);
    assertThat(incomplete.missingRequiredRefs()).contains("missing-dependency", "not-present");

    MessageEnvelope cycleA =
        VerificationFixtures.fact(
            "cycle-a", "cycle alpha", EvidenceType.NATURAL_PROOF_AUDITED, List.of("cycle-b"));
    MessageEnvelope cycleB =
        VerificationFixtures.fact(
            "cycle-b", "cycle beta", EvidenceType.NATURAL_PROOF_AUDITED, List.of("cycle-a"));
    var cycle =
        ContextSelectionPolicy.select(
            List.of(cycleA, cycleB),
            ignored -> true,
            "cycle",
            100_000,
            5,
            ContextPurpose.SYNTHESIS,
            List.of("cycle-a"),
            null);
    assertThat(cycle.requiredContextComplete()).isFalse();
    assertThat(cycle.missingRequiredRefs()).contains("cycle-a");
  }

  @Test
  void rankingCentralityDedupAndCharacterTruncationAreDeterministic() {
    MessageEnvelope base =
        with(
            VerificationFixtures.fact(
                "central", "shared algebra lemma", EvidenceType.FORMAL_KERNEL_CERTIFICATE, List.of()),
            List.of("assumption"),
            List.of("scope"),
            List.of(),
            List.of("r1", "r2", "r3", "r4", "r5"));
    MessageEnvelope first =
        VerificationFixtures.fact(
            "first", "target keyword", EvidenceType.UNVERIFIED_IDEA, List.of("central"));
    MessageEnvelope second =
        VerificationFixtures.fact(
            "second", "target keyword", EvidenceType.NATURAL_PROOF_AUDITED, List.of("central"));
    MessageEnvelope external =
        VerificationFixtures.fact(
            "external-only", "target", EvidenceType.BOUNDED_EXPERIMENT, List.of("external:oracle"));
    List<MessageEnvelope> messages = List.of(first, second, base, external);
    Map<String, ObjectNode> provenance =
        Map.of("central", VerificationFixtures.object().put("review", "ok"));

    var selection =
        ContextSelectionPolicy.select(
            messages,
            ignored -> true,
            "target keyword",
            100_000,
            4,
            ContextPurpose.FINAL_VERIFICATION,
            List.of("central", base.contentHash(), "external:skip", " "),
            provenance);
    assertThat(selection.requiredContextComplete()).isTrue();
    assertThat(selection.selectedMessageIds()).doesNotHaveDuplicates();
    assertThat(selection.selectedMessageIds()).contains("central", "first", "second", "external-only");
    assertThat(selection.usedChars()).isPositive();
    assertThat(selection.truncated()).isFalse();

    var truncated =
        ContextSelectionPolicy.select(
            messages,
            ignored -> true,
            null,
            700,
            2,
            ContextPurpose.SYNTHESIS,
            null,
            null);
    assertThat(truncated.selectedMessageIds()).hasSizeLessThanOrEqualTo(2);
    assertThat(truncated.truncated()).isTrue();

    MessageEnvelope blank =
        VerificationFixtures.fact(
            "blank-query", "symbols", EvidenceType.NUMERICAL_HEURISTIC, List.of());
    assertThat(
            ContextSelectionPolicy.select(
                    List.of(blank),
                    ignored -> true,
                    "",
                    100_000,
                    1,
                    ContextPurpose.SYNTHESIS,
                    List.of(),
                    null)
                .selectedMessageIds())
        .containsExactly("blank-query");
  }

  private static MessageEnvelope with(
      MessageEnvelope source,
      List<String> assumptions,
      List<String> scope,
      List<String> artifacts,
      List<String> targets) {
    return new MessageEnvelope(
        artifacts,
        assumptions,
        source.conclusion(),
        "",
        source.createdAt(),
        source.dependencies(),
        source.dependencyRefs(),
        source.evidenceType(),
        source.memoryTier(),
        source.messageId(),
        source.messageType(),
        source.normalizationConfidence(),
        source.normalizedStatement(),
        source.problemHash(),
        source.quantifiers(),
        source.rawSourceRef(),
        source.roundCreated(),
        source.schemaVersion(),
        scope,
        source.sourceAgentId(),
        source.sourceRole(),
        source.sourceRouteId(),
        source.statement(),
        targets,
        source.ttlRounds(),
        source.variableBindings(),
        source.verificationConfidence(),
        source.verificationStatus());
  }
}
