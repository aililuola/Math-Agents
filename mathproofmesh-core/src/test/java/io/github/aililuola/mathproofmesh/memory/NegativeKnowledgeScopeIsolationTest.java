package io.github.aililuola.mathproofmesh.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class NegativeKnowledgeScopeIsolationTest {
  @Test
  void exactKeysBindProblemAssumptionsQuantifiersBindingsAndScope() {
    NegativeKnowledgeRegistry registry = new NegativeKnowledgeRegistry();
    NegativeKnowledgeCandidate exact =
        NegativeKnowledgeFixtures.candidate(
            NegativeKnowledgeFixtures.PROBLEM_A,
            "For every n, P(n) holds.",
            NegativeKnowledgeTargetType.CLAIM,
            List.of("n is positive"),
            List.of(NegativeKnowledgeFixtures.forallN()),
            List.of(NegativeKnowledgeFixtures.bindingN()),
            List.of("all positive integers"),
            NegativeKnowledgeSurface.PROOF_OBLIGATION_CREATION,
            NegativeCandidateIntent.PROOF_TARGET);
    registry.registerDeterministicGuardrail(
        NegativeKnowledgeFixtures.PROBLEM_A,
        DeterministicNegativeSeed.trustedCodeSeed(
            "scoped-guardrail",
            NegativeKnowledgeTargetType.CLAIM,
            exact.statement(),
            List.of(),
            exact.assumptions(),
            exact.quantifiers(),
            exact.variableBindings(),
            exact.scopeLimitations(),
            "deterministically invalid in this exact scope"),
        0);

    assertThat(registry.decide(exact, 0).code())
        .isEqualTo(NegativeKnowledgeDecisionCode.BLOCK_PERMANENT);
    assertThat(
            registry
                .decide(
                    NegativeKnowledgeFixtures.candidate(
                        NegativeKnowledgeFixtures.PROBLEM_B,
                        exact.statement(),
                        exact.targetType(),
                        exact.assumptions(),
                        exact.quantifiers(),
                        exact.variableBindings(),
                        exact.scopeLimitations(),
                        exact.surface(),
                        exact.intent()),
                    0)
                .code())
        .isEqualTo(NegativeKnowledgeDecisionCode.ALLOW);
    assertThat(
            registry
                .decide(
                    NegativeKnowledgeFixtures.candidate(
                        NegativeKnowledgeFixtures.PROBLEM_A,
                        exact.statement(),
                        exact.targetType(),
                        List.of("n is positive", "the counterexample is excluded"),
                        exact.quantifiers(),
                        exact.variableBindings(),
                        List.of("n greater than the counterexample"),
                        exact.surface(),
                        exact.intent()),
                    0)
                .code())
        .isEqualTo(NegativeKnowledgeDecisionCode.ALLOW);
  }
}
