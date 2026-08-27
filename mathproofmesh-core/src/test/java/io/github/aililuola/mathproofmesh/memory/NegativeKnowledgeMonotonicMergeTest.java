package io.github.aililuola.mathproofmesh.memory;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NegativeKnowledgeMonotonicMergeTest {
  @Test
  void strongerEvidenceUpgradesOneSemanticRecordAndLaterWeakDuplicatesCannotDowngradeIt() {
    NegativeKnowledgeRegistry registry = new NegativeKnowledgeRegistry();
    NegativeKnowledgeCandidate candidate =
        NegativeKnowledgeFixtures.candidate(
            NegativeKnowledgeFixtures.PROBLEM_A,
            "The proposed invariant is false.",
            NegativeKnowledgeTargetType.CLAIM,
            NegativeKnowledgeSurface.FACT_PROMOTION,
            NegativeCandidateIntent.FACT_PROMOTION);

    NegativeKnowledgeRecord temporary =
        registry.registerTemporaryRejection(candidate, "model-rejection", 0, 2);
    var counterexample =
        NegativeKnowledgeFixtures.counterexample("verified-counterexample", candidate.statement());
    NegativeKnowledgeRecord upgraded =
        registry.registerVerifiedCounterexample(
            counterexample,
            NegativeKnowledgeFixtures.verifiedAuthority(
                "verified-counterexample", candidate.statement()));
    NegativeKnowledgeRecord afterWeakDuplicate =
        registry.registerTemporaryRejection(candidate, "later-weak-message", 16, 2);

    assertThat(registry.records()).hasSize(1);
    assertThat(upgraded.negativeId()).isEqualTo(temporary.negativeId());
    assertThat(afterWeakDuplicate.permanent()).isTrue();
    assertThat(afterWeakDuplicate.expiresAfterRound()).isNull();
    assertThat(afterWeakDuplicate.kinds())
        .containsExactlyInAnyOrder(
            NegativeKnowledgeKind.TEMPORARY_HYPOTHESIS_REJECTION,
            NegativeKnowledgeKind.VERIFIED_COUNTEREXAMPLE);
    assertThat(afterWeakDuplicate.evidenceMessageIds())
        .contains("model-rejection", "verified-counterexample", "later-weak-message");
    assertThat(afterWeakDuplicate.version()).isGreaterThan(temporary.version());
  }
}
