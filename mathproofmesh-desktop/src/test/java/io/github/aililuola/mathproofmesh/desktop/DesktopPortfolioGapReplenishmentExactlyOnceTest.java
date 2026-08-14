package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.contract.StrategySet;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopPortfolioGapReplenishmentExactlyOnceTest {
  @TempDir Path temporaryDirectory;

  @Test
  void requestsAtMostOneGapFillAndNeverRepeatsItAfterRestore() throws Exception {
    List<StrategyCard> initial =
        java.util.stream.IntStream.range(0, 4)
            .mapToObj(
                index ->
                    DesktopStrategyPortfolioTestHarness.strategy(
                        "duplicate-" + index,
                        "Duplicate presentation " + index,
                        "Delete one leaf and reuse the same induction bridge",
                        "Leaf deletion preserves the induction invariant.",
                        0.90d))
            .toList();
    StrategySet initialSet =
        new StrategySet("Four presentations of one mechanism.", List.of(), initial);
    StrategySet supplement =
        new StrategySet(
            "Two missing structural mechanisms.",
            List.of(),
            List.of(
                DesktopStrategyPortfolioTestHarness.strategy(
                    "gap-extremal",
                    "Extremal supplement",
                    "Choose a longest path and analyze its endpoints",
                    "A longest path endpoint in a finite tree is a leaf.",
                    0.60d),
                DesktopStrategyPortfolioTestHarness.strategy(
                    "gap-counting",
                    "Counting supplement",
                    "Apply the degree sum identity to count leaves",
                    "The degree sum of a finite tree equals twice its edge count.",
                    0.58d)));
    Path runDirectory = temporaryDirectory.resolve("gap");
    DesktopSolveCheckpoint checkpoint;
    DesktopStrategyPortfolioTestHarness.ProductionState beforeRestore;
    int replenishmentRequests;
    long replenishmentProviderCalls;
    int replenishmentCandidates;
    int duplicateReplenishmentCandidates;
    try (DesktopStrategyPortfolioTestHarness harness =
        DesktopStrategyPortfolioTestHarness.open(
            runDirectory, "portfolio-gap-once", List.of(initialSet, supplement))) {
      harness.freeze();
      harness.generateAndAdmit();

      assertThat(harness.providerStrategyCalls()).isEqualTo(2);
      assertThat(harness.replenishmentProviderCalls()).isEqualTo(1);
      assertThat(harness.providerRequests())
          .filteredOn(
              request ->
                  request
                      .messages()
                      .getLast()
                      .content()
                      .matches(
                          "(?s).*\\\"generation_mode\\\"\\s*:\\s*"
                              + "\\\"portfolio_gap_replenishment\\\".*"))
          .singleElement()
          .satisfies(
              request ->
                  assertThat(request.messages().getLast().content())
                      .contains("portfolio_gap_replenishment"));
      assertThat(harness.replenishments().snapshot().episodes().values())
          .singleElement()
          .satisfies(
              record -> {
                assertThat(record.providerCalls()).isEqualTo(1);
                assertThat(record.completed()).isTrue();
                assertThat(record.candidateIds()).containsExactly("gap-extremal", "gap-counting");
              });
      assertThat(harness.admittedStrategies()).hasSizeGreaterThanOrEqualTo(2);
      var replenishmentRecords = harness.replenishments().snapshot().episodes().values();
      replenishmentRequests = replenishmentRecords.size();
      replenishmentProviderCalls =
          replenishmentRecords.stream().mapToLong(record -> record.providerCalls()).sum();
      List<String> replenishedIds =
          replenishmentRecords.stream().flatMap(record -> record.candidateIds().stream()).toList();
      replenishmentCandidates = replenishedIds.size();
      duplicateReplenishmentCandidates =
          replenishedIds.size() - replenishedIds.stream().distinct().toList().size();
      checkpoint = harness.checkpointRoundTrip();
      beforeRestore = harness.state();
    }

    try (DesktopStrategyPortfolioTestHarness restored =
        DesktopStrategyPortfolioTestHarness.open(runDirectory, "portfolio-gap-once")) {
      restored.restore(checkpoint);
      restored.generateAndAdmit();
      assertThat(restored.providerStrategyCalls()).isZero();
      assertThat(restored.replenishmentProviderCalls()).isZero();
      assertThat(restored.state()).isEqualTo(beforeRestore);
      assertThat(restored.replenishments().snapshot().episodes().values())
          .singleElement()
          .satisfies(record -> assertThat(record.providerCalls()).isEqualTo(1));
      long postRestoreCalls = restored.replenishmentProviderCalls();
      int duplicateRoutes =
          restored.state().routeIds().size()
              - restored.state().routeIds().stream().distinct().toList().size();
      System.out.println("PORTFOLIO GAP REPLENISHMENT DIAGNOSTIC");
      System.out.println("REPLENISHMENT_REQUESTS=" + replenishmentRequests);
      System.out.println("REPLENISHMENT_PROVIDER_CALLS=" + replenishmentProviderCalls);
      System.out.println("REPLENISHMENT_CANDIDATES=" + replenishmentCandidates);
      System.out.println("SECOND_REPLENISHMENT_CALLS=" + postRestoreCalls);
      System.out.println("POST_RESTORE_REPLENISHMENT_CALLS=" + postRestoreCalls);
      System.out.println(
          "DUPLICATE_REPLENISHMENT_CANDIDATES=" + duplicateReplenishmentCandidates);
      System.out.println("DUPLICATE_ROUTE_CREATIONS=" + duplicateRoutes);
      System.out.println("RESULT=PASS");
    }
  }
}
