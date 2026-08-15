package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.MechanismOperationKind;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopOutOfVocabularyMechanismDuplicateTest {
  @TempDir Path temp;

  @Test
  void unseenFreeTextCannotBypassOneTypedMechanismIdentity() throws Exception {
    List<String> prose =
        List.of(
            "Select a non-extendable geodesic and inspect its boundary points.",
            "Fix a route whose continuation set is empty and study its termini.",
            "Take a chain saturated under inclusion and compare its boundary objects.",
            "Choose a walk admitting no strict prolongation and analyze both ends.",
            "Work with an inclusion-saturated simple route and inspect its extremities.",
            "Anchor a route outside every extension image and use its endpoints.");
    List<StrategyCard> candidates =
        java.util.stream.IntStream.range(0, prose.size())
            .mapToObj(
                index ->
                    DesktopStrategyPortfolioTestHarness.withOperation(
                        DesktopStrategyPortfolioTestHarness.strategy(
                            "oov-desktop-" + index,
                            "Presentation " + index,
                            prose.get(index),
                            "The two boundary vertices are leaves.",
                            0.8d),
                        MechanismOperationKind.EXTREMAL_SELECTION))
            .toList();

    try (DesktopStrategyPortfolioTestHarness harness =
        DesktopStrategyPortfolioTestHarness.open(temp, "oov-mechanism-duplicate")) {
      harness.freeze();
      harness.setStrategies(candidates);
      harness.generateAndAdmit();

      int admissions = harness.admittedStrategies().size();
      int leaks = Math.max(0, admissions - 1);
      System.out.println("OUT_OF_VOCABULARY_SAME_MECHANISM_CANDIDATES=" + candidates.size());
      System.out.println("ADMISSIONS=" + admissions);
      System.out.println("PARAPHRASE_BYPASS_LEAKS=" + leaks);
      assertThat(candidates).hasSize(6);
      assertThat(admissions).isEqualTo(1);
      assertThat(leaks).isZero();
    }
  }
}
