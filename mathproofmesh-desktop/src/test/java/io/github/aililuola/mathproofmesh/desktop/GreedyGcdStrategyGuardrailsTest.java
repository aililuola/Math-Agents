package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.memory.GreedyGcdNegativeKnowledgeSeeds;
import io.github.aililuola.mathproofmesh.memory.NegativeCandidateIntent;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeCandidate;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeRegistry;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeSurface;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeTargetType;
import java.util.List;
import org.junit.jupiter.api.Test;

final class GreedyGcdStrategyGuardrailsTest {
  private static final String PROBLEM_HASH = "8".repeat(64);

  @Test
  void rejectsRoutesWhoseLoadBearingStepIsFinitePrimeSupport() {
    assertBlocked("Prove that the sequence has finite prime support.");
    assertBlocked("Only finitely many primes divide terms of the sequence.");
  }

  @Test
  void rejectsTheOtherPersistedNegativeMemoryPatterns() {
    assertBlocked("Assume one prime divides every term and derive periodicity.");
    assertBlocked("Use containment of residue classes for different moduli.");
    assertBlocked("A bounded search proves the sequence is eventually periodic.");
  }

  @Test
  void allowsTheTwoSoundFoundationLemmasAndTheExplicitBridgeObligation() {
    assertAllowed("Let Q=rad(a_1); every multiple of Q is admissible, so the gaps are bounded.");
    assertAllowed(
        "Define an explicit finite state and prove separately that recurrence implies translation periodicity.");
  }

  private static void assertBlocked(String statement) {
    assertThat(decisions(statement)).anyMatch(decision -> !decision.allowed());
  }

  private static void assertAllowed(String statement) {
    assertThat(decisions(statement)).allMatch(io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeDecision::allowed);
  }

  private static List<io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeDecision> decisions(
      String statement) {
    NegativeKnowledgeRegistry registry = new NegativeKnowledgeRegistry();
    GreedyGcdNegativeKnowledgeSeeds.all()
        .forEach(seed -> registry.registerDeterministicGuardrail(PROBLEM_HASH, seed, 0));
    return java.util.Arrays.stream(NegativeKnowledgeTargetType.values())
        .map(
            targetType ->
                registry.decide(
                    new NegativeKnowledgeCandidate(
                        PROBLEM_HASH,
                        targetType,
                        statement,
                        "",
                        List.of(),
                        List.of(),
                        List.of(),
                        GreedyGcdNegativeKnowledgeSeeds.problemScope(),
                        NegativeKnowledgeSurface.STRATEGY_ADMISSION,
                        NegativeCandidateIntent.POSITIVE_DEPENDENCY),
                    0))
        .toList();
  }
}
