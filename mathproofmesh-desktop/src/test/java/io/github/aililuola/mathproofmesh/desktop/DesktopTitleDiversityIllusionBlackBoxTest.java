package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.memory.TypedMemory;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class DesktopTitleDiversityIllusionBlackBoxTest {
  @Test
  void differentTitlesCannotMultiplyOneStructuralMechanism() {
    List<StrategyCard> candidates =
        IntStream.rangeClosed(1, 6)
            .mapToObj(
                index ->
                    StrategyDiversityIssue007BlackBoxSupport.strategy(
                        "title-illusion-" + index,
                        "Presentation " + index,
                        "Delete a leaf and apply the same induction dependency DAG",
                        "Deleting a leaf preserves the induction invariant.",
                        0.8d,
                        "wording-" + index))
            .toList();

    List<StrategyCard> selected =
        StrategyDiversityIssue007BlackBoxSupport.selectWithIssue007Pipeline(
            candidates, 4, new TypedMemory(), Set.of());

    System.out.println("RAW_CANDIDATES=" + candidates.size());
    System.out.println("TRUE_MECHANISMS=1");
    System.out.println("EXPECTED_SELECTED=1");
    System.out.println("ACTUAL_SELECTED=" + selected.size());
    assertThat(selected).hasSize(1);
  }
}
