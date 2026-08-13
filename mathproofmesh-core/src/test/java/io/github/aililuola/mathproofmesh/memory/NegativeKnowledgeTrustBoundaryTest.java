package io.github.aililuola.mathproofmesh.memory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NegativeKnowledgeTrustBoundaryTest {
  @Test
  void modelAuthoredCounterexampleFieldsNeverGrantPermanentAuthority() {
    TypedMemory memory = new TypedMemory();
    var untrusted =
        NegativeKnowledgeFixtures.negative(
            "model-counterexample",
            NegativeKnowledgeFixtures.PROBLEM_A,
            "A model-declared counterexample target.",
            0,
            2,
            "model-agent",
            io.github.aililuola.mathproofmesh.contract.EvidenceType.COUNTEREXAMPLE,
            java.util.List.of("experiment://model-counterexample"),
            "model-raw-ref",
            java.util.List.of(),
            java.util.List.of(),
            java.util.List.of(),
            NegativeKnowledgeFixtures.GREEDY_SCOPE);

    memory.addNegative(untrusted);
    memory.applyCounterexample(untrusted);

    NegativeKnowledgeRecord record =
        memory.negativeKnowledgeRegistry().records().getFirst();
    assertThat(record.permanent()).isFalse();
    assertThat(record.kinds())
        .containsExactly(NegativeKnowledgeKind.TEMPORARY_HYPOTHESIS_REJECTION);
  }

  @Test
  void independentlyReplayedRefutationCanUpgradeAContentDuplicatePermanently() {
    TypedMemory memory = new TypedMemory();
    var counterexample =
        NegativeKnowledgeFixtures.counterexample(
            "trusted-counterexample", "A verified target is false.");
    memory.addNegative(counterexample);

    memory.applyVerifiedCounterexample(
        counterexample,
        NegativeKnowledgeFixtures.verifiedAuthority(
            "trusted-counterexample", counterexample.normalizedStatement()));

    assertThat(memory.negativeKnowledgeRegistry().records()).singleElement()
        .satisfies(
            record -> {
              assertThat(record.permanent()).isTrue();
              assertThat(record.kinds())
                  .contains(NegativeKnowledgeKind.VERIFIED_COUNTEREXAMPLE);
            });
  }
}
