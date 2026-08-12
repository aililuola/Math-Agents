package io.github.aililuola.mathproofmesh.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.contract.EvidenceType;
import io.github.aililuola.mathproofmesh.contract.MemoryTier;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import java.util.List;
import org.junit.jupiter.api.Test;

class TypedMemoryParityTest {

  @Test
  void numericalEvidenceStaysOutOfFactTier() {
    TypedMemory memory = new TypedMemory();
    MessageEnvelope heuristic =
        MemoryFixtures.insight(
            "heuristic",
            "numerical pattern",
            "route-a",
            "author-a",
            EvidenceType.NUMERICAL_HEURISTIC,
            0.9);
    memory.addInsight(heuristic);

    assertThatThrownBy(
            () -> memory.promote("heuristic", "independent-referee", 0.95))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot be promoted");
    assertThat(memory.facts()).isEmpty();
    assertThat(memory.insights()).containsExactly(heuristic);
  }

  @Test
  void counterexampleInvalidatesFactAndTransitiveDependentsIdempotently() {
    TypedMemory memory = new TypedMemory();
    MessageEnvelope base =
        MemoryFixtures.fact("base", "claim p", "route-a", "author-a", List.of());
    memory.addFact(base, "referee-a");
    MessageEnvelope dependent =
        MemoryFixtures.fact(
            "dependent",
            "claim q",
            "route-b",
            "author-b",
            List.of(base.messageId()));
    memory.addFact(dependent, "referee-b");
    MessageEnvelope counterexample =
        MemoryFixtures.counterexample(
            "counterexample", "claim p", List.of(base.messageId()));
    memory.addNegative(counterexample);

    List<String> first = memory.applyCounterexample(counterexample);
    List<String> replay = memory.applyCounterexample(counterexample);

    assertThat(first).containsExactly("base", "dependent");
    assertThat(replay).isEqualTo(first);
    assertThat(memory.facts()).isEmpty();
    assertThat(memory.negatives())
        .extracting(MessageEnvelope::messageId)
        .contains("counterexample", "base", "dependent");
  }

  @Test
  void duplicateProvenanceDoesNotUpgradeInsight() {
    TypedMemory memory = new TypedMemory();
    MessageEnvelope first =
        MemoryFixtures.insight(
            "first",
            "same idea",
            "route-a",
            "author-a",
            EvidenceType.UNVERIFIED_IDEA,
            0.4);
    MessageEnvelope second =
        MemoryFixtures.insight(
            "second",
            "same idea",
            "route-a",
            "author-b",
            EvidenceType.UNVERIFIED_IDEA,
            0.8);

    memory.addMessage(first);
    MessageEnvelope merged = memory.addMessage(second);

    assertThat(merged.messageId()).isEqualTo("first");
    assertThat(memory.provenance("first")).containsExactly("author-a", "author-b");
    assertThat(memory.tier("first")).isEqualTo(MemoryTier.INSIGHT);
  }

  @Test
  void authorCannotReviewOwnPromotion() {
    TypedMemory memory = new TypedMemory();
    MessageEnvelope insight =
        MemoryFixtures.insight(
            "candidate",
            "candidate theorem",
            "route-a",
            "author-a",
            EvidenceType.NATURAL_PROOF_AUDITED,
            0.95);
    memory.addInsight(insight);

    assertThatThrownBy(() -> memory.promote("candidate", "author-a", 0.95))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("author cannot");
  }

  @Test
  void boundedExperimentCannotMasqueradeAsFact() {
    TypedMemory memory = new TypedMemory();
    MessageEnvelope candidate =
        MemoryFixtures.insight(
            "finite-sample",
            "tested first one hundred values",
            "route-a",
            "author-a",
            EvidenceType.BOUNDED_EXPERIMENT,
            1.0);
    memory.addInsight(candidate);

    assertThatThrownBy(
            () -> memory.promote("finite-sample", "referee-a", 1.0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot be promoted");
  }

  @Test
  void missingDependencyBlocksFactAdmission() {
    TypedMemory memory = new TypedMemory();
    MessageEnvelope invalid =
        MemoryFixtures.fact(
            "derived",
            "derived statement",
            "route-a",
            "author-a",
            List.of("missing"));

    assertThatThrownBy(() -> memory.addFact(invalid, "referee-a"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("dependencies are unresolved");
  }

  @Test
  void knownCounterexampleBlocksLaterPromotion() {
    TypedMemory memory = new TypedMemory();
    MessageEnvelope counterexample =
        MemoryFixtures.counterexample("counterexample", "claim p", List.of());
    memory.addNegative(counterexample);
    MessageEnvelope candidate =
        MemoryFixtures.insight(
            "candidate",
            "claim p",
            "route-a",
            "author-a",
            EvidenceType.NATURAL_PROOF_AUDITED,
            0.95);
    memory.addInsight(candidate);

    assertThatThrownBy(
            () -> memory.promote("candidate", "referee-a", 0.95))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("counterexample");
  }

  @Test
  void snapshotRestoreRetainsTiersProvenanceAndInvalidationAudit() {
    TypedMemory memory = new TypedMemory();
    MessageEnvelope fact =
        MemoryFixtures.fact("fact", "claim p", "route-a", "author-a", List.of());
    memory.addFact(fact, "referee-a");
    MessageEnvelope counterexample =
        MemoryFixtures.counterexample("counterexample", "claim p", List.of());
    memory.addNegative(counterexample);
    memory.applyCounterexample(counterexample);

    TypedMemory restored =
        TypedMemory.restore(memory.snapshot(), MemoryPolicy.defaults());

    assertThat(restored.tier("fact")).isEqualTo(MemoryTier.NEGATIVE);
    assertThat(restored.find("fact").orElseThrow().verificationStatus())
        .isEqualTo(ClaimStatus.REJECTED);
    assertThat(restored.invalidationReason("fact"))
        .contains("counterexample:counterexample");
    assertThat(restored.audit()).isNotEmpty();
  }

  @Test
  void routeContextsAreBoundedAndNegativesAreGlobal() {
    TypedMemory memory = new TypedMemory(new MemoryPolicy(0.8, 1, 1, 1));
    memory.addInsight(
        MemoryFixtures.insight(
            "a",
            "idea a",
            "route-a",
            "author-a",
            EvidenceType.UNVERIFIED_IDEA,
            0.2));
    memory.addInsight(
        MemoryFixtures.insight(
            "b",
            "idea b",
            "route-a",
            "author-b",
            EvidenceType.UNVERIFIED_IDEA,
            0.2));
    memory.addNegative(
        MemoryFixtures.counterexample("negative", "other route fact", List.of()));

    assertThat(memory.insightsForRoute("route-a")).hasSize(1);
    assertThat(memory.negativesForRoute("route-z"))
        .extracting(MessageEnvelope::messageId)
        .containsExactly("negative");
  }
}
