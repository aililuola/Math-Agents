package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.StrategySet;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DesktopStrategyGenerationGuidanceDomainNeutralTest {
  @TempDir Path temporaryDirectory;

  @Test
  void realStrategyGenerationPromptsStayGenericAcrossThreeUnrelatedDomains()
      throws Exception {
    List<String> problems =
        List.of(
            "Prove that every finite tree with at least two vertices has two leaves.",
            "Prove the rank-nullity theorem for a linear map between finite-dimensional vector spaces.",
            "Prove that a surjection between finite sets of equal cardinality is a bijection.");
    List<String> forbidden =
        List.of(
            "bounded gaps",
            "finite-state periodicity",
            "prime support",
            "hitting sets",
            "translation periodicity");
    StrategySet response =
        new StrategySet(
            "Generic structured routes.",
            List.of(),
            DesktopStrategyPortfolioTestHarness.fourIndependent("prompt"));

    for (int index = 0; index < problems.size(); index++) {
      try (DesktopStrategyPortfolioTestHarness harness =
          DesktopStrategyPortfolioTestHarness.open(
              temporaryDirectory.resolve("domain-" + index),
              "domain-neutral-guidance-" + index,
              problems.get(index),
              List.of(response))) {
        harness.freeze();
        harness.generateAndAdmit();
        String prompt =
            harness.providerRequests().getFirst().messages().getLast().content().toLowerCase(Locale.ROOT);

        assertThat(prompt).contains("mathematical objects", "required", "supporting", "falsification");
        forbidden.forEach(term -> assertThat(prompt).doesNotContain(term));
      }
    }
  }
}
