package io.github.aililuola.mathproofmesh.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class NegativeKnowledgeAdmissionGateTest {
  @Test
  void permanentExactAndTrustedAliasesBlockEveryPositiveSurfaceButAllowFalsification() {
    NegativeKnowledgeRegistry registry = new NegativeKnowledgeRegistry();
    registry.registerDeterministicGuardrail(
        NegativeKnowledgeFixtures.PROBLEM_A,
        NegativeKnowledgeFixtures.deterministicSeed(),
        0);
    NegativeKnowledgeAdmissionGate gate = new NegativeKnowledgeAdmissionGate(registry);

    for (NegativeKnowledgeSurface surface :
        List.of(
            NegativeKnowledgeSurface.STRATEGY_ADMISSION,
            NegativeKnowledgeSurface.ROUTE_WIDENING,
            NegativeKnowledgeSurface.ROUTE_REVISION,
            NegativeKnowledgeSurface.PROOF_OBLIGATION_CREATION,
            NegativeKnowledgeSurface.INSPIRATION_MATERIALIZATION,
            NegativeKnowledgeSurface.FACT_PROMOTION,
            NegativeKnowledgeSurface.RESTORE_REVALIDATION)) {
      NegativeKnowledgeDecision decision =
          gate.evaluate(
              NegativeKnowledgeFixtures.candidate(
                  NegativeKnowledgeFixtures.PROBLEM_A,
                  NegativeKnowledgeFixtures.FINITE_PRIMES,
                  NegativeKnowledgeTargetType.CLAIM,
                  surface,
                  NegativeCandidateIntent.POSITIVE_DEPENDENCY),
              4);
      assertThat(decision.code()).isEqualTo(NegativeKnowledgeDecisionCode.BLOCK_PERMANENT);
      assertThat(decision.matchStrength()).isEqualTo(NegativeMatchStrength.TRUSTED_ALIAS);
    }

    NegativeKnowledgeDecision falsification =
        gate.evaluate(
            NegativeKnowledgeFixtures.candidate(
                NegativeKnowledgeFixtures.PROBLEM_A,
                NegativeKnowledgeFixtures.FINITE_PRIMES,
                NegativeKnowledgeTargetType.CLAIM,
                NegativeKnowledgeSurface.PROOF_OBLIGATION_CREATION,
                NegativeCandidateIntent.FALSIFICATION_ONLY),
            4);
    assertThat(falsification.code())
        .isEqualTo(NegativeKnowledgeDecisionCode.ALLOW_FALSIFICATION_ONLY);
    assertThat(falsification.allowed()).isTrue();
  }

  @Test
  void merelyPossibleEquivalenceIsQuarantinedInsteadOfDeclaredEqual() {
    NegativeKnowledgeRegistry registry = new NegativeKnowledgeRegistry();
    registry.registerDeterministicGuardrail(
        NegativeKnowledgeFixtures.PROBLEM_A,
        NegativeKnowledgeFixtures.deterministicSeed(),
        0);

    NegativeKnowledgeDecision decision =
        registry.decide(
            NegativeKnowledgeFixtures.candidate(
                NegativeKnowledgeFixtures.PROBLEM_A,
                "The sequence has finite prime support under the proposed route argument.",
                NegativeKnowledgeTargetType.CLAIM,
                NegativeKnowledgeSurface.STRATEGY_ADMISSION,
                NegativeCandidateIntent.POSITIVE_DEPENDENCY),
            0);

    assertThat(decision.code())
        .isEqualTo(NegativeKnowledgeDecisionCode.QUARANTINE_POSSIBLE_EQUIVALENT);
    assertThat(decision.matchStrength()).isEqualTo(NegativeMatchStrength.POSSIBLE_EQUIVALENT);
  }
}
