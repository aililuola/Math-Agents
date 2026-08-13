package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.memory.GreedyGcdNegativeKnowledgeSeeds;
import io.github.aililuola.mathproofmesh.memory.NegativeCandidateIntent;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeCandidate;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeDecisionCode;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeRegistry;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeSurface;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeTargetType;
import java.util.List;
import org.junit.jupiter.api.Test;

final class GreedyGcdNegativeKnowledgeSeedsTest {
  private static final String PROBLEM_HASH = "2".repeat(64);

  @Test
  void exposesFourTypedPermanentSeedsWithTheRequiredFinitePrimeAliases() {
    var seeds = GreedyGcdNegativeKnowledgeSeeds.all();

    assertThat(seeds).hasSize(4);
    assertThat(seeds)
        .extracting(seed -> seed.seedId())
        .containsExactly(
            "finite-prime-support",
            "universal-prefix-prime",
            "cross-modulus-containment",
            "finite-sample-periodicity");
    assertThat(GreedyGcdNegativeKnowledgeSeeds.finitePrimeSupport().trustedAliases())
        .contains(
            "The entire sequence contains only finitely many prime divisors.",
            "The sequence has finite prime support.",
            "Only finitely many primes divide terms of the sequence.",
            "\u5e8f\u5217\u4e2d\u51fa\u73b0\u7684\u6240\u6709\u8d28\u56e0\u5b50\u53ea\u6709\u6709\u9650\u591a\u4e2a\u3002",
            "\u5e8f\u5217\u7684\u7d20\u6570\u56e0\u5b50\u96c6\u5408\u6709\u9650\u3002");
    assertThat(seeds)
        .allSatisfy(
            seed -> {
              assertThat(seed.reason()).isNotBlank();
              assertThat(seed.scopeLimitations()).isNotEmpty();
            });
  }

  @Test
  void trustedAliasesAreBlockedByTheRegistryWhileSoundFoundationsRemainAdmissible() {
    NegativeKnowledgeRegistry registry = new NegativeKnowledgeRegistry();
    GreedyGcdNegativeKnowledgeSeeds.all()
        .forEach(seed -> registry.registerDeterministicGuardrail(PROBLEM_HASH, seed, 0));

    for (String alias :
        GreedyGcdNegativeKnowledgeSeeds.finitePrimeSupport().trustedAliases()) {
      assertThat(registry.decide(candidate(alias), 20).code())
          .isEqualTo(NegativeKnowledgeDecisionCode.BLOCK_PERMANENT);
    }
    assertThat(
            registry
                .decide(
                    candidate(
                        "Let Q=rad(a_1); every multiple of Q is admissible, so the gaps are bounded."),
                    20)
                .code())
        .isEqualTo(NegativeKnowledgeDecisionCode.ALLOW);
    assertThat(
            registry
                .decide(
                    candidate(
                        "Define a finite state and prove separately that recurrence implies translation periodicity."),
                    20)
                .code())
        .isEqualTo(NegativeKnowledgeDecisionCode.ALLOW);
  }

  private static NegativeKnowledgeCandidate candidate(String statement) {
    return new NegativeKnowledgeCandidate(
        PROBLEM_HASH,
        NegativeKnowledgeTargetType.CLAIM,
        statement,
        "",
        List.of(),
        List.of(),
        List.of(),
        GreedyGcdNegativeKnowledgeSeeds.problemScope(),
        NegativeKnowledgeSurface.STRATEGY_ADMISSION,
        NegativeCandidateIntent.POSITIVE_DEPENDENCY);
  }
}
