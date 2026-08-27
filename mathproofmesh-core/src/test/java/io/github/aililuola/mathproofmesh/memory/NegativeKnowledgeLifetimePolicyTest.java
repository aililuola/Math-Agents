package io.github.aililuola.mathproofmesh.memory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NegativeKnowledgeLifetimePolicyTest {
  @Test
  void temporaryKnowledgeExpiresAfterItsMathematicalLifetimeWithoutPretendingToBePermanent() {
    NegativeKnowledgeRegistry registry = new NegativeKnowledgeRegistry();
    NegativeKnowledgeCandidate candidate =
        NegativeKnowledgeFixtures.candidate(
            NegativeKnowledgeFixtures.PROBLEM_A,
            "A provisional route hypothesis is unusable.",
            NegativeKnowledgeTargetType.CLAIM,
            NegativeKnowledgeSurface.STRATEGY_ADMISSION,
            NegativeCandidateIntent.POSITIVE_DEPENDENCY);

    NegativeKnowledgeRecord record =
        registry.registerTemporaryRejection(candidate, "temporary-evidence", 0, 2);

    assertThat(record.kinds())
        .containsExactly(NegativeKnowledgeKind.TEMPORARY_HYPOTHESIS_REJECTION);
    assertThat(record.permanent()).isFalse();
    assertThat(record.expiresAfterRound()).isEqualTo(2);
    assertThat(record.activeAt(0)).isTrue();
    assertThat(record.activeAt(1)).isTrue();
    assertThat(record.activeAt(2)).isTrue();
    assertThat(record.activeAt(3)).isFalse();
    assertThat(registry.decide(candidate, 2).code())
        .isEqualTo(NegativeKnowledgeDecisionCode.BLOCK_TEMPORARY);
    assertThat(registry.decide(candidate, 3).code())
        .isEqualTo(NegativeKnowledgeDecisionCode.ALLOW);
  }
}
