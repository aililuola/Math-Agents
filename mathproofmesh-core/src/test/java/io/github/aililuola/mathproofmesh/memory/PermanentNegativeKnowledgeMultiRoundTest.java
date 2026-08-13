package io.github.aililuola.mathproofmesh.memory;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PermanentNegativeKnowledgeMultiRoundTest {
  private static final int ROUNDS = 30;
  private static final int RESTORE_ROUND = 15;
  private static final List<NegativeKnowledgeSurface> SURFACES =
      List.of(
          NegativeKnowledgeSurface.STRATEGY_ADMISSION,
          NegativeKnowledgeSurface.PROOF_OBLIGATION_CREATION,
          NegativeKnowledgeSurface.ROUTE_REVISION,
          NegativeKnowledgeSurface.INSPIRATION_MATERIALIZATION,
          NegativeKnowledgeSurface.FACT_PROMOTION);

  @Test
  void permanentKnowledgeBlocksOneHundredFiftyReentriesAcrossRestore() {
    NegativeKnowledgeRegistry registry = new NegativeKnowledgeRegistry();
    for (DeterministicNegativeSeed seed : GreedyGcdNegativeKnowledgeSeeds.all()) {
      registry.registerDeterministicGuardrail(
          NegativeKnowledgeFixtures.PROBLEM_A, seed, 0);
    }
    var verified =
        NegativeKnowledgeFixtures.counterexample(
            "verified-counterexample", "A separate exact target is false.");
    registry.registerVerifiedCounterexample(
        verified,
        NegativeKnowledgeFixtures.verifiedAuthority(
            "verified-counterexample", verified.normalizedStatement()));
    NegativeKnowledgeCandidate temporaryCandidate =
        NegativeKnowledgeFixtures.candidate(
            NegativeKnowledgeFixtures.PROBLEM_A,
            "Temporary route rejection.",
            NegativeKnowledgeTargetType.CLAIM,
            NegativeKnowledgeSurface.STRATEGY_ADMISSION,
            NegativeCandidateIntent.POSITIVE_DEPENDENCY);
    registry.registerTemporaryRejection(temporaryCandidate, "temporary", 0, 2);
    int seededGuardrails =
        (int)
            registry.records().stream()
                .filter(
                    record ->
                        record.kinds().contains(NegativeKnowledgeKind.DETERMINISTIC_GUARDRAIL))
                .count();
    int verifiedCounterexamples =
        (int)
            registry.records().stream()
                .filter(
                    record ->
                        record.kinds().contains(NegativeKnowledgeKind.VERIFIED_COUNTEREXAMPLE))
                .count();
    int temporaryNegatives =
        (int) registry.records().stream().filter(record -> !record.permanent()).count();

    int reentryAttempts = 0;
    Map<NegativeKnowledgeSurface, Integer> blocks = new EnumMap<>(NegativeKnowledgeSurface.class);
    int postRestoreLeaks = 0;
    int permanentDowngrades = 0;
    int crossProblemFalseBlocks = 0;
    int scopeFalseBlocks = 0;
    int falsificationAllows = 0;
    int unrelatedAdmissions = 0;
    int activeRouteLeaks = 0;
    int proofGraphLeaks = 0;
    int revisionLineageLeaks = 0;
    int inspirationMemoryLeaks = 0;
    int pendingTaskLeaks = 0;
    int factMemoryLeaks = 0;
    String preRestoreHash = null;
    String postRestoreHash = null;
    List<String> aliases = GreedyGcdNegativeKnowledgeSeeds.finitePrimeSupport().trustedAliases();

    for (int round = 0; round < ROUNDS; round++) {
      if (round == RESTORE_ROUND) {
        preRestoreHash = registry.registryHash();
        NegativeKnowledgeSnapshot decoded =
            ContractObjectMapper.read(
                ContractObjectMapper.write(registry.snapshot()),
                NegativeKnowledgeSnapshot.class);
        registry = NegativeKnowledgeRegistry.restore(decoded);
        postRestoreHash = registry.registryHash();
        assertThat(postRestoreHash).isEqualTo(preRestoreHash);
      }
      if (round == 16) {
        NegativeKnowledgeCandidate duplicate =
            NegativeKnowledgeFixtures.candidate(
                NegativeKnowledgeFixtures.PROBLEM_A,
                aliases.getFirst(),
                NegativeKnowledgeTargetType.CLAIM,
                NegativeKnowledgeSurface.STRATEGY_ADMISSION,
                NegativeCandidateIntent.POSITIVE_DEPENDENCY);
        registry.registerTemporaryRejection(duplicate, "round-16-weak-duplicate", round, 2);
      }

      for (int index = 0; index < SURFACES.size(); index++) {
        NegativeKnowledgeSurface surface = SURFACES.get(index);
        NegativeCandidateIntent intent =
            surface == NegativeKnowledgeSurface.FACT_PROMOTION
                ? NegativeCandidateIntent.FACT_PROMOTION
                : surface == NegativeKnowledgeSurface.PROOF_OBLIGATION_CREATION
                    ? NegativeCandidateIntent.PROOF_TARGET
                    : NegativeCandidateIntent.POSITIVE_DEPENDENCY;
        NegativeKnowledgeCandidate candidate =
            NegativeKnowledgeFixtures.candidate(
                NegativeKnowledgeFixtures.PROBLEM_A,
                aliases.get((round + index) % aliases.size()),
                NegativeKnowledgeTargetType.CLAIM,
                surface,
                intent);
        NegativeKnowledgeDecision decision = registry.decide(candidate, round);
        reentryAttempts++;
        if (decision.code() == NegativeKnowledgeDecisionCode.BLOCK_PERMANENT) {
          blocks.merge(surface, 1, Integer::sum);
        } else {
          if (round >= RESTORE_ROUND) {
            postRestoreLeaks++;
          }
          switch (surface) {
            case STRATEGY_ADMISSION -> activeRouteLeaks++;
            case PROOF_OBLIGATION_CREATION -> proofGraphLeaks++;
            case ROUTE_REVISION -> revisionLineageLeaks++;
            case INSPIRATION_MATERIALIZATION -> {
              inspirationMemoryLeaks++;
              pendingTaskLeaks++;
            }
            case FACT_PROMOTION -> factMemoryLeaks++;
            default -> throw new AssertionError("unexpected diagnostic surface: " + surface);
          }
        }
      }

      if (registry.records().stream()
          .filter(record -> record.kinds().contains(NegativeKnowledgeKind.DETERMINISTIC_GUARDRAIL))
          .anyMatch(record -> !record.permanent() || record.expiresAfterRound() != null)) {
        permanentDowngrades++;
      }
      NegativeKnowledgeCandidate falsification =
          NegativeKnowledgeFixtures.candidate(
              NegativeKnowledgeFixtures.PROBLEM_A,
              aliases.get(round % aliases.size()),
              NegativeKnowledgeTargetType.CLAIM,
              NegativeKnowledgeSurface.PROOF_OBLIGATION_CREATION,
              NegativeCandidateIntent.FALSIFICATION_ONLY);
      if (registry.decide(falsification, round).code()
          == NegativeKnowledgeDecisionCode.ALLOW_FALSIFICATION_ONLY) {
        falsificationAllows++;
      }
    }

    for (NegativeKnowledgeSurface surface : SURFACES) {
      NegativeKnowledgeCandidate unrelated =
          NegativeKnowledgeFixtures.candidate(
              NegativeKnowledgeFixtures.PROBLEM_A,
              "A valid unrelated candidate for " + surface.name(),
              NegativeKnowledgeTargetType.CLAIM,
              surface,
              NegativeCandidateIntent.POSITIVE_DEPENDENCY);
      if (registry.decide(unrelated, ROUNDS).code() == NegativeKnowledgeDecisionCode.ALLOW) {
        unrelatedAdmissions++;
      }
    }
    if (registry
            .decide(
                NegativeKnowledgeFixtures.candidate(
                    NegativeKnowledgeFixtures.PROBLEM_B,
                    aliases.getFirst(),
                    NegativeKnowledgeTargetType.CLAIM,
                    NegativeKnowledgeSurface.STRATEGY_ADMISSION,
                    NegativeCandidateIntent.POSITIVE_DEPENDENCY),
                ROUNDS)
            .code()
        != NegativeKnowledgeDecisionCode.ALLOW) {
      crossProblemFalseBlocks++;
    }
    if (registry
            .decide(
                NegativeKnowledgeFixtures.candidate(
                    NegativeKnowledgeFixtures.PROBLEM_A,
                    aliases.getFirst(),
                    NegativeKnowledgeTargetType.CLAIM,
                    List.of("the counterexample is excluded"),
                    List.of(),
                    List.of(),
                    List.of("restricted scope excluding the refuted case"),
                    NegativeKnowledgeSurface.STRATEGY_ADMISSION,
                    NegativeCandidateIntent.POSITIVE_DEPENDENCY),
                ROUNDS)
            .code()
        != NegativeKnowledgeDecisionCode.ALLOW) {
      scopeFalseBlocks++;
    }

    assertThat(registry.decide(temporaryCandidate, 0).code())
        .isEqualTo(NegativeKnowledgeDecisionCode.BLOCK_TEMPORARY);
    assertThat(registry.decide(temporaryCandidate, 2).code())
        .isEqualTo(NegativeKnowledgeDecisionCode.BLOCK_TEMPORARY);
    assertThat(registry.decide(temporaryCandidate, 3).code())
        .isEqualTo(NegativeKnowledgeDecisionCode.ALLOW);
    assertThat(reentryAttempts).isEqualTo(150);
    SURFACES.forEach(surface -> assertThat(blocks.getOrDefault(surface, 0)).isEqualTo(30));
    assertThat(postRestoreLeaks).isZero();
    assertThat(permanentDowngrades).isZero();
    assertThat(crossProblemFalseBlocks).isZero();
    assertThat(scopeFalseBlocks).isZero();
    assertThat(falsificationAllows).isEqualTo(30);
    assertThat(unrelatedAdmissions).isEqualTo(5);
    assertThat(activeRouteLeaks).isZero();
    assertThat(proofGraphLeaks).isZero();
    assertThat(revisionLineageLeaks).isZero();
    assertThat(inspirationMemoryLeaks).isZero();
    assertThat(pendingTaskLeaks).isZero();
    assertThat(factMemoryLeaks).isZero();

    System.out.println("PERMANENT NEGATIVE KNOWLEDGE DIAGNOSTIC");
    System.out.println("ROUNDS=" + ROUNDS);
    System.out.println("RESTORE_ROUND=" + RESTORE_ROUND);
    System.out.println("SEEDED_DETERMINISTIC_GUARDRAILS=" + seededGuardrails);
    System.out.println("VERIFIED_COUNTEREXAMPLES=" + verifiedCounterexamples);
    System.out.println("TEMPORARY_NEGATIVES=" + temporaryNegatives);
    System.out.println("TEMPORARY_EXPIRED_AT_ROUND=3");
    System.out.println("REENTRY_ATTEMPTS=" + reentryAttempts);
    System.out.println("STRATEGY_BLOCKS=" + blocks.getOrDefault(NegativeKnowledgeSurface.STRATEGY_ADMISSION, 0));
    System.out.println("OBLIGATION_BLOCKS=" + blocks.getOrDefault(NegativeKnowledgeSurface.PROOF_OBLIGATION_CREATION, 0));
    System.out.println("REVISION_BLOCKS=" + blocks.getOrDefault(NegativeKnowledgeSurface.ROUTE_REVISION, 0));
    System.out.println("INSPIRATION_BLOCKS=" + blocks.getOrDefault(NegativeKnowledgeSurface.INSPIRATION_MATERIALIZATION, 0));
    System.out.println("FACT_PROMOTION_BLOCKS=" + blocks.getOrDefault(NegativeKnowledgeSurface.FACT_PROMOTION, 0));
    System.out.println("ACTIVE_ROUTE_LEAKS=" + activeRouteLeaks);
    System.out.println("PROOF_GRAPH_LEAKS=" + proofGraphLeaks);
    System.out.println("REVISION_LINEAGE_LEAKS=" + revisionLineageLeaks);
    System.out.println("INSPIRATION_MEMORY_LEAKS=" + inspirationMemoryLeaks);
    System.out.println("PENDING_TASK_LEAKS=" + pendingTaskLeaks);
    System.out.println("FACT_MEMORY_LEAKS=" + factMemoryLeaks);
    System.out.println("POST_RESTORE_LEAKS=" + postRestoreLeaks);
    System.out.println("PERMANENT_DOWNGRADES=" + permanentDowngrades);
    System.out.println("CROSS_PROBLEM_FALSE_BLOCKS=" + crossProblemFalseBlocks);
    System.out.println("SCOPE_FALSE_BLOCKS=" + scopeFalseBlocks);
    System.out.println("FALSIFICATION_ONLY_ALLOWS=" + falsificationAllows);
    System.out.println("UNRELATED_ADMISSIONS=" + unrelatedAdmissions);
    System.out.println("PRE_RESTORE_REGISTRY_HASH=" + preRestoreHash);
    System.out.println("POST_RESTORE_REGISTRY_HASH=" + postRestoreHash);
    System.out.println("RESULT=PASS");
  }
}
