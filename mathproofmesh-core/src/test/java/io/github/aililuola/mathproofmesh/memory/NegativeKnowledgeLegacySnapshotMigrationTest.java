package io.github.aililuola.mathproofmesh.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.EvidenceType;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NegativeKnowledgeLegacySnapshotMigrationTest {
  @Test
  void missingV5RegistryMigratesOnlyTrustedLegacyEvidenceToPermanentKnowledge() {
    MessageEnvelope deterministic =
        NegativeKnowledgeFixtures.negative(
            "guardrail-finite-prime-support",
            NegativeKnowledgeFixtures.PROBLEM_A,
            "The entire sequence contains only finitely many prime divisors.",
            0,
            2,
            "deterministic-preflight",
            EvidenceType.UNVERIFIED_IDEA,
            List.of(),
            "deterministic://greedy-gcd-guardrails/finite-prime-support",
            List.of(),
            List.of(),
            List.of(),
            NegativeKnowledgeFixtures.GREEDY_SCOPE);
    MessageEnvelope verified =
        NegativeKnowledgeFixtures.counterexample(
            "legacy-verified-counterexample", "A replayed claim is false.");
    MessageEnvelope ordinary =
        NegativeKnowledgeFixtures.temporary(
            "legacy-ordinary-negative", "A weak route hypothesis was rejected.", 0, 2);
    Map<String, MessageEnvelope> messages = new LinkedHashMap<>();
    messages.put(deterministic.messageId(), deterministic);
    messages.put(verified.messageId(), verified);
    messages.put(ordinary.messageId(), ordinary);
    Map<String, io.github.aililuola.mathproofmesh.contract.MemoryTier> tiers =
        messages.keySet().stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    key -> key,
                    ignored -> io.github.aililuola.mathproofmesh.contract.MemoryTier.NEGATIVE,
                    (left, right) -> left,
                    LinkedHashMap::new));
    TypedMemorySnapshot legacy =
        new TypedMemorySnapshot(
            messages,
            tiers,
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            List.of());
    ObjectNode json = (ObjectNode) ContractObjectMapper.toTree(legacy);
    json.remove("negativeKnowledge");
    TypedMemorySnapshot decoded =
        ContractObjectMapper.read(ContractObjectMapper.write(json), TypedMemorySnapshot.class);

    TypedMemory restored = TypedMemory.restore(decoded, MemoryPolicy.defaults());

    assertThat(restored.negativeKnowledgeRegistry().records()).hasSize(3);
    assertThat(restored.negativeKnowledgeRegistry().records())
        .filteredOn(record -> record.statement().equals(deterministic.normalizedStatement()))
        .singleElement()
        .satisfies(
            record -> {
              assertThat(record.permanent()).isTrue();
              assertThat(record.kinds())
                  .contains(NegativeKnowledgeKind.DETERMINISTIC_GUARDRAIL);
            });
    assertThat(restored.negativeKnowledgeRegistry().records())
        .filteredOn(record -> record.statement().equals(verified.normalizedStatement()))
        .singleElement()
        .satisfies(
            record -> {
              assertThat(record.permanent()).isTrue();
              assertThat(record.kinds())
                  .contains(NegativeKnowledgeKind.VERIFIED_COUNTEREXAMPLE);
            });
    assertThat(restored.negativeKnowledgeRegistry().records())
        .filteredOn(record -> record.statement().equals(ordinary.normalizedStatement()))
        .singleElement()
        .satisfies(
            record -> {
              assertThat(record.permanent()).isFalse();
              assertThat(record.kinds())
                  .containsExactly(NegativeKnowledgeKind.TEMPORARY_HYPOTHESIS_REJECTION);
            });
  }
}
