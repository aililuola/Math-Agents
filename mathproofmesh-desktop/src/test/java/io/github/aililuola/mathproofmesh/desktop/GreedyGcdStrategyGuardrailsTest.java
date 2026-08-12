package io.github.aililuola.mathproofmesh.desktop;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import java.util.List;
import org.junit.jupiter.api.Test;

final class GreedyGcdStrategyGuardrailsTest {
  @Test
  void rejectsRoutesWhoseLoadBearingStepIsFinitePrimeSupport() {
    assertTrue(
        GreedyGcdStrategyGuardrails.violates(
            strategy(
                "限制素数集合并利用模M周期性",
                "证明P有限需要精细论证。",
                List.of("prime_factors", "finiteness"))));
    assertTrue(
        GreedyGcdStrategyGuardrails.violates(
            strategy(
                "Potential-function proof",
                "Prove that the sequence has finite prime support.",
                List.of("prime_finiteness"))));
  }

  @Test
  void rejectsTheOtherPersistedNegativeMemoryPatterns() {
    assertTrue(
        GreedyGcdStrategyGuardrails.violates(
            strategy(
                "Universal divisor",
                "Assume one prime divides every term and derive periodicity.",
                List.of())));
    assertTrue(
        GreedyGcdStrategyGuardrails.violates(
            strategy(
                "Residue nesting",
                "Use containment of residue classes for different moduli.",
                List.of())));
    assertTrue(
        GreedyGcdStrategyGuardrails.violates(
            strategy(
                "Finite experiment",
                "A bounded search proves eventual translation periodicity.",
                List.of())));
  }

  @Test
  void allowsTheTwoSoundFoundationLemmasAndTheExplicitBridgeObligation() {
    assertFalse(
        GreedyGcdStrategyGuardrails.violates(
            strategy(
                "Bounded gaps",
                "Let Q = rad(a_1); every multiple of Q is admissible, so a_{n+1}-a_n <= Q.",
                List.of("bounded_gaps"))));
    assertFalse(
        GreedyGcdStrategyGuardrails.violates(
            strategy(
                "Finite-state bridge",
                "Define an explicit finite state and prove separately that state recurrence implies translation periodicity.",
                List.of("bridge", "finite_state"))));
  }

  private static StrategyCard strategy(String title, String bottleneck, List<String> tags) {
    return new StrategyCard(
        null,
        bottleneck,
        List.of(),
        List.of(),
        List.of(),
        "Use the stated mechanism without importing another unproved dependency.",
        List.of(),
        0.2d,
        0.6d,
        List.of(),
        "Search for the smallest counterexample.",
        "The dependency graph is explicit.",
        null,
        null,
        List.of(),
        List.of(),
        null,
        tags,
        title);
  }
}
