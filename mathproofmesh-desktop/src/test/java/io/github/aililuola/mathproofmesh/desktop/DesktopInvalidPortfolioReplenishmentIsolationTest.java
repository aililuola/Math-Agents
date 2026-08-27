package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.contract.StrategySet;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopInvalidPortfolioReplenishmentIsolationTest {
  @TempDir Path temporaryDirectory;

  @Test
  void invalidOptionalReplenishmentIsAuditedWithoutDiscardingThePreparedSourcePortfolio()
      throws Exception {
    List<StrategyCard> initial =
        java.util.stream.IntStream.range(0, 4)
            .mapToObj(
                index ->
                    DesktopStrategyPortfolioTestHarness.strategy(
                        "source-" + index,
                        "Duplicate presentation " + index,
                        "Delete one leaf and reuse the same induction bridge",
                        "Leaf deletion preserves the induction invariant.",
                        0.90d))
            .toList();
    StrategySet initialSet =
        new StrategySet("Four presentations of one mechanism.", List.of(), initial);
    StrategySet validSupplement =
        new StrategySet(
            "One optional structural supplement.",
            List.of(),
            List.of(
                DesktopStrategyPortfolioTestHarness.strategy(
                    "invalid-gap-candidate",
                    "Optional extremal supplement",
                    "Choose a longest path and analyze its endpoints",
                    "A longest path endpoint in a finite tree is a leaf.",
                    0.60d)));
    String invalidSupplement =
        ContractObjectMapper.write(validSupplement)
            .replace("\"polarity\":\"positive\"", "\"polarity\":\"conclusion\"");
    Path runDirectory = temporaryDirectory.resolve("invalid-optional-replenishment");
    DesktopSolveCheckpoint checkpoint;
    DesktopStrategyPortfolioTestHarness.ProductionState beforeRestore;

    try (DesktopStrategyPortfolioTestHarness harness =
        DesktopStrategyPortfolioTestHarness.openWithRawStrategyResponses(
            runDirectory,
            "invalid-optional-replenishment",
            initialSet,
            List.of(invalidSupplement, invalidSupplement))) {
      harness.freeze();
      DesktopStrategyPortfolioTestHarness.ProtectedHashes before = harness.protectedHashes();

      assertThatCode(harness::generateAndAdmit).doesNotThrowAnyException();

      DesktopStrategyPortfolioTestHarness.ProtectedHashes after = harness.protectedHashes();
      List<String> admittedIds =
          harness.admittedStrategies().stream().map(StrategyCard::strategyId).toList();
      List<String> routeIds = harness.routeStrategyIds();
      long invalidCandidateLeaks =
          harness.candidates().snapshot().records().keySet().stream()
              .filter(id -> id.startsWith("invalid-gap"))
              .count();

      assertThat(harness.providerStrategyCalls()).isEqualTo(3);
      assertThat(harness.replenishmentProviderCalls()).isEqualTo(1L);
      assertThat(harness.replenishments().snapshot().episodes().values())
          .singleElement()
          .satisfies(
              record -> {
                assertThat(record.providerCalls()).isEqualTo(1);
                assertThat(record.completed()).isTrue();
                assertThat(record.candidateIds()).isEmpty();
              });
      assertThat(admittedIds).isNotEmpty().allMatch(id -> id.startsWith("source-"));
      assertThat(routeIds).isNotEmpty().allMatch(id -> id.startsWith("source-"));
      assertThat(invalidCandidateLeaks).isZero();
      assertThat(after.root()).isEqualTo(before.root());
      assertThat(after.negative()).isEqualTo(before.negative());
      assertThat(after.attempts()).isEqualTo(before.attempts());
      assertThat(after.claims()).isEqualTo(before.claims());

      checkpoint = harness.checkpointRoundTrip();
      beforeRestore = harness.state();

      System.out.println("INVALID OPTIONAL PORTFOLIO REPLENISHMENT DIAGNOSTIC");
      System.out.println("REPLENISHMENT_PROVIDER_CALLS=" + harness.replenishmentProviderCalls());
      System.out.println("INVALID_REPLENISHMENT_CANDIDATE_LEAKS=" + invalidCandidateLeaks);
      System.out.println("SOURCE_PORTFOLIO_ADMISSIONS=" + admittedIds.size());
      System.out.println("SOURCE_ROUTE_ADMISSIONS=" + routeIds.size());
      System.out.println("ROOT_HASH_CHANGES=0");
      System.out.println("NEGATIVE_REGISTRY_HASH_CHANGES=0");
    }

    try (DesktopStrategyPortfolioTestHarness restored =
        DesktopStrategyPortfolioTestHarness.open(
            runDirectory, "invalid-optional-replenishment")) {
      restored.restore(checkpoint);
      restored.generateAndAdmit();

      assertThat(restored.providerStrategyCalls()).isZero();
      assertThat(restored.replenishmentProviderCalls()).isZero();
      assertThat(restored.state()).isEqualTo(beforeRestore);
      assertThat(restored.replenishments().snapshot().episodes().values())
          .singleElement()
          .satisfies(
              record -> {
                assertThat(record.completed()).isTrue();
                assertThat(record.candidateIds()).isEmpty();
              });

      System.out.println("POST_RESTORE_REPLENISHMENT_CALLS=0");
      System.out.println("POST_RESTORE_STATE_DRIFTS=0");
      System.out.println("RESULT=PASS");
    }
  }
}
