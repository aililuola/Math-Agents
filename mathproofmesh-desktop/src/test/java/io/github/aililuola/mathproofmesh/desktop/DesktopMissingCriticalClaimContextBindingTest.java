package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyCandidateStatus;
import io.github.aililuola.mathproofmesh.strategydiversity.StrategyDiversityConfig;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopMissingCriticalClaimContextBindingTest {
  @TempDir Path temp;

  @Test
  void everyNewCriticalClaimRequiresOneExplicitContextBinding() throws Exception {
    StrategyCard complete = DesktopClaimContextTestSupport.strategy();
    StrategyCard missing = withBindings(complete, complete.criticalClaimContextBindings().subList(0, 4));
    int missingBindings =
        missing.criticalClaims().size() - missing.criticalClaimContextBindings().size();
    int incompleteAdmissions;
    int activeLeaks;

    try (DesktopStrategyPortfolioTestHarness harness =
        DesktopStrategyPortfolioTestHarness.open(temp.resolve("missing"), "missing-claim-binding")) {
      harness.freeze();
      harness.setDiversityConfig(
          StrategyDiversityConfig.defaults().withQualityGate(0.0d, 0.0d, 0.0d));
      harness.setStrategies(List.of(missing));
      harness.generateAndAdmit();
      incompleteAdmissions = harness.admittedStrategies().size();
      activeLeaks = harness.state().routeIds().size();
      assertThat(harness.candidates().find(missing.strategyId()))
          .hasValueSatisfying(
              record -> {
                assertThat(record.status()).isEqualTo(StrategyCandidateStatus.REJECTED_INVALID);
                assertThat(record.detail()).contains("MISSING_CRITICAL_CLAIM_CONTEXT_BINDING");
              });
    }

    int completeAdmissions;
    try (DesktopStrategyPortfolioTestHarness harness =
        DesktopStrategyPortfolioTestHarness.open(temp.resolve("complete"), "complete-claim-binding")) {
      harness.freeze();
      harness.setDiversityConfig(
          StrategyDiversityConfig.defaults().withQualityGate(0.0d, 0.0d, 0.0d));
      harness.setStrategies(List.of(complete));
      harness.generateAndAdmit();
      completeAdmissions = harness.admittedStrategies().size();
    }

    System.out.println("MISSING_BINDINGS=" + missingBindings);
    System.out.println("INCOMPLETE_CANDIDATE_ADMISSIONS=" + incompleteAdmissions);
    System.out.println("ACTIVE_STATE_LEAKS=" + activeLeaks);
    System.out.println("COMPLETE_CANDIDATE_ADMISSIONS=" + completeAdmissions);
    assertThat(missingBindings).isOne();
    assertThat(incompleteAdmissions).isZero();
    assertThat(activeLeaks).isZero();
    assertThat(completeAdmissions).isOne();
  }

  private static StrategyCard withBindings(
      StrategyCard source,
      List<io.github.aililuola.mathproofmesh.contract.CriticalClaimContextBinding> bindings) {
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
        source.mechanismOperations(),
        bindings);
  }
}
