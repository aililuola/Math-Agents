package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopParaphrasedMechanismDuplicateBlackBoxTest {
  @TempDir Path temp;

  @Test
  void paraphrasingOneInductiveReductionCannotMultiplyActiveRoutes() throws Exception {
    List<String> mechanisms =
        List.of(
            "Delete a leaf and apply induction on the number of vertices",
            "Remove a pendant vertex and induct on the graph order",
            "Strip one terminal vertex, then use the inductive hypothesis",
            "Take away an endpoint and perform vertex-count induction",
            "Pass recursively to a one-vertex-shorter object and lift the result",
            "Prune a pendant vertex and reduce to the smaller-order tree");
    List<StrategyCard> candidates =
        java.util.stream.IntStream.range(0, mechanisms.size())
            .mapToObj(
                index ->
                    DesktopStrategyPortfolioTestHarness.strategy(
                        "paraphrase-" + index,
                        "Presentation " + index,
                        mechanisms.get(index),
                        "Removing a terminal vertex leaves a smaller tree.",
                        0.8d))
            .toList();

    try (DesktopStrategyPortfolioTestHarness harness =
        DesktopStrategyPortfolioTestHarness.open(temp, "paraphrase-black-box")) {
      harness.freeze();
      harness.setStrategies(candidates);
      harness.generateAndAdmit();

      int admissions = harness.admittedStrategies().size();
      System.out.println("PARAPHRASED_SAME_MECHANISM_CANDIDATES=" + candidates.size());
      System.out.println("PARAPHRASED_SAME_MECHANISM_ADMISSIONS=" + admissions);
      System.out.println("PARAPHRASE_BYPASS_LEAKS=" + Math.max(0, admissions - 1));
      assertThat(admissions).isEqualTo(1);
    }
  }
}
