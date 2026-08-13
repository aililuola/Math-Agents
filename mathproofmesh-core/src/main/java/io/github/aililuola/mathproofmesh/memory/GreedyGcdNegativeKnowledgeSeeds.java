package io.github.aililuola.mathproofmesh.memory;

import java.util.List;

public final class GreedyGcdNegativeKnowledgeSeeds {
  private static final List<String> PROBLEM_SCOPE =
      List.of("submitted greedy-GCD sequence problem");

  private static final DeterministicNegativeSeed FINITE_PRIME_SUPPORT =
      DeterministicNegativeSeed.trustedCodeSeed(
          "finite-prime-support",
          NegativeKnowledgeTargetType.CLAIM,
          "The entire sequence contains only finitely many prime divisors.",
          List.of(
              "The entire sequence contains only finitely many prime divisors.",
              "The sequence has finite prime support.",
              "Only finitely many primes divide terms of the sequence.",
              "\u5e8f\u5217\u4e2d\u51fa\u73b0\u7684\u6240\u6709\u8d28\u56e0\u5b50\u53ea\u6709\u6709\u9650\u591a\u4e2a\u3002",
              "\u5e8f\u5217\u7684\u7d20\u6570\u56e0\u5b50\u96c6\u5408\u6709\u9650\u3002"),
          List.of(),
          List.of(),
          List.of(),
          PROBLEM_SCOPE,
          "The recurrence does not establish finite prime support; new prime divisors may occur.");

  private static final DeterministicNegativeSeed UNIVERSAL_PREFIX_PRIME =
      DeterministicNegativeSeed.trustedCodeSeed(
          "universal-prefix-prime",
          NegativeKnowledgeTargetType.CLAIM,
          "There is one prime that divides every term, or all terms of every prefix.",
          List.of(
              "One prime divides every term.",
              "One prime divides every prefix.",
              "A common prime divides all terms."),
          List.of(),
          List.of(),
          List.of(),
          PROBLEM_SCOPE,
          "The greedy condition supplies a divisor locally and does not make one prime universal.");

  private static final DeterministicNegativeSeed CROSS_MODULUS_CONTAINMENT =
      DeterministicNegativeSeed.trustedCodeSeed(
          "cross-modulus-containment",
          NegativeKnowledgeTargetType.INFERENCE_PATTERN,
          "Residue-class sets for distinct moduli may be compared using ordinary set containment.",
          List.of(
              "Containment of residue classes for different moduli.",
              "Residue classes for distinct moduli are nested."),
          List.of(),
          List.of(),
          List.of(),
          PROBLEM_SCOPE,
          "Residue classes live in different quotient spaces unless a valid comparison map is supplied.");

  private static final DeterministicNegativeSeed FINITE_SAMPLE_PERIODICITY =
      DeterministicNegativeSeed.trustedCodeSeed(
          "finite-sample-periodicity",
          NegativeKnowledgeTargetType.INFERENCE_PATTERN,
          "A finite computation that finds no counterexample proves eventual translation periodicity.",
          List.of(
              "A finite sample proves eventual periodicity.",
              "A bounded search proves the sequence is eventually periodic."),
          List.of(),
          List.of(),
          List.of(),
          PROBLEM_SCOPE,
          "Finite non-refutation is exploratory evidence, not a proof of an unbounded conclusion.");

  private static final List<DeterministicNegativeSeed> ALL =
      List.of(
          FINITE_PRIME_SUPPORT,
          UNIVERSAL_PREFIX_PRIME,
          CROSS_MODULUS_CONTAINMENT,
          FINITE_SAMPLE_PERIODICITY);

  private GreedyGcdNegativeKnowledgeSeeds() {}

  public static List<DeterministicNegativeSeed> all() {
    return ALL;
  }

  public static DeterministicNegativeSeed finitePrimeSupport() {
    return FINITE_PRIME_SUPPORT;
  }

  public static List<String> problemScope() {
    return PROBLEM_SCOPE;
  }

  public static java.util.Optional<DeterministicNegativeSeed> byId(String seedId) {
    return ALL.stream().filter(seed -> seed.seedId().equals(seedId)).findFirst();
  }
}
