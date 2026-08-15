package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.CriticalClaimContextBinding;
import io.github.aililuola.mathproofmesh.contract.MechanismOperationDeclaration;
import io.github.aililuola.mathproofmesh.contract.MechanismOperationKind;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.contract.StrategySet;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopUnknownMechanismCannotPadPortfolioTest {
  @TempDir Path temp;

  @Test
  void unresolvedOperationGraphsCannotCountAsIndependentPortfolioMechanisms() throws Exception {
    List<StrategyCard> initial = unknownCandidates("unknown-initial", 6);
    List<StrategyCard> supplement = unknownCandidates("unknown-supplement", 2);

    try (DesktopStrategyPortfolioTestHarness harness =
        DesktopStrategyPortfolioTestHarness.open(
            temp,
            "unknown-mechanism-padding",
            List.of(
                new StrategySet("Unknown initial candidates.", List.of(), initial),
                new StrategySet("Unknown replenishment candidates.", List.of(), supplement)))) {
      harness.freeze();
      harness.generateAndAdmit();

      int admissions = harness.admittedStrategies().size();
      long quarantined =
          harness.candidates().snapshot().records().values().stream()
              .filter(
                  record ->
                      "QUARANTINED_MECHANISM_UNRESOLVED".equals(record.status().name()))
              .count();
      long replenishments = harness.replenishmentProviderCalls();
      int padding = Math.min(4, admissions);

      System.out.println("UNKNOWN_MECHANISM_CANDIDATES=" + initial.size());
      System.out.println("UNKNOWN_MECHANISM_QUARANTINES=" + quarantined);
      System.out.println("UNKNOWN_MECHANISM_DISTINCT_ADMISSIONS=" + admissions);
      System.out.println("UNKNOWN_MECHANISM_PORTFOLIO_PADDING=" + padding);
      System.out.println("REPLENISHMENT_REQUESTS=" + replenishments);
      assertThat(initial).hasSize(6);
      assertThat(quarantined).isEqualTo(initial.size() + supplement.size());
      assertThat(admissions).isZero();
      assertThat(padding).isZero();
      assertThat(replenishments).isEqualTo(1L);
    }
  }

  private static List<StrategyCard> unknownCandidates(String prefix, int count) {
    return java.util.stream.IntStream.range(0, count)
        .mapToObj(
            index ->
                unresolvedOperationGraph(
                    DesktopStrategyPortfolioTestHarness.strategy(
                        prefix + '-' + index,
                        "Unknown presentation " + index,
                        "Use an unspecified transformation number " + index,
                        "Route-local bridge " + index + " holds.",
                        0.95d - index * 0.01d),
                    index % 2 == 0))
        .toList();
  }

  private static StrategyCard unresolvedOperationGraph(StrategyCard source, boolean empty) {
    String claimId = source.criticalClaims().getFirst().claimId();
    return new StrategyCard(
        source.assignedAgentId(),
        source.bottleneck(),
        source.calculationChecks(),
        source.calculationEvidenceRefs(),
        source.computationHints(),
        source.coreIdea(),
        source.criticalClaims(),
        source.estimatedCost(),
        source.estimatedSuccess(),
        source.expectedLemmas(),
        source.falsificationTest(),
        source.independenceBasis(),
        source.inspirationProposalId(),
        source.keyOriginalStep(),
        source.parentStrategyIds(),
        source.prerequisites(),
        source.strategyId(),
        source.tags(),
        source.title(),
        empty
            ? List.of()
            : List.of(
                new MechanismOperationDeclaration(
                    "unresolved-mechanism",
                    MechanismOperationKind.UNKNOWN,
                    List.of("@roots"),
                    List.of("@direct_targets"))),
        List.of(
            new CriticalClaimContextBinding(
                claimId, "@claim", List.of(), List.of(), List.of(), List.of(), List.of(), "positive")));
  }
}
