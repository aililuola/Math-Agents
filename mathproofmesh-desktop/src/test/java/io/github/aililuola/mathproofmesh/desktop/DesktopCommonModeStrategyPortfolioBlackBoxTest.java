package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.memory.TypedMemory;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DesktopCommonModeStrategyPortfolioBlackBoxTest {
  @Test
  void unresolvedRequiredClaimCapsTheWholeCommonModeGroup() {
    String common = "Every fiber of the finite surjection has exactly one element.";
    List<StrategyCard> candidates = new ArrayList<>();
    for (int index = 1; index <= 5; index++) {
      candidates.add(
          StrategyDiversityIssue007BlackBoxSupport.strategy(
              "common-" + index,
              "Distinct looking route " + index,
              "Surface mechanism " + index + " with a different representation and proof vocabulary",
              common,
              0.99d,
              "surface-" + index));
    }
    for (int index = 1; index <= 3; index++) {
      candidates.add(
          StrategyDiversityIssue007BlackBoxSupport.strategy(
              "independent-" + index,
              "Independent route " + index,
              "Independent finite-set mechanism " + index,
              "Independent load-bearing claim " + index,
              0.55d,
              "independent-" + index));
    }

    List<StrategyCard> selected =
        StrategyDiversityIssue007BlackBoxSupport.selectWithIssue007Pipeline(
            candidates,
            5,
            new TypedMemory(),
            Set.of(StrategyDiversityIssue007BlackBoxSupport.VERIFIED_FACT));
    long selectedFromGroup =
        selected.stream().filter(strategy -> strategy.strategyId().startsWith("common-")).count();

    System.out.println("UNRESOLVED_COMMON_MODE_GROUP_SIZE=5");
    System.out.println("EXPECTED_MAX_SELECTED_FROM_GROUP=1");
    System.out.println("ACTUAL_SELECTED_FROM_GROUP=" + selectedFromGroup);
    assertThat(selectedFromGroup).isLessThanOrEqualTo(1L);
  }
}
