package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.memory.TypedMemory;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DesktopTrustedCounterexamplePreflightIgnoredBlackBoxTest {
  @Test
  void trustedExactCounterexampleMustRunBeforePortfolioAdmission() {
    TypedMemory memory =
        StrategyDiversityIssue007BlackBoxSupport.memoryWithVerifiedCounterexample();
    StrategyCard refuted =
        StrategyDiversityIssue007BlackBoxSupport.strategy(
            "hamiltonian-route",
            "High-confidence Hamiltonian shortcut",
            "Close the target through a Hamiltonian cycle",
            StrategyDiversityIssue007BlackBoxSupport.REFUTED_CLAIM,
            0.99d,
            "hamiltonian");

    List<StrategyCard> selected =
        StrategyDiversityIssue007BlackBoxSupport.selectWithIssue007Pipeline(
            List.of(refuted), 1, memory, Set.of());

    System.out.println(
        "VERIFIED_REFUTED_REQUIRED_CLAIMS="
            + memory.negativeKnowledgeRegistry().records().size());
    System.out.println("EXPECTED_ADMISSIONS=0");
    System.out.println("ACTUAL_ADMISSIONS=" + selected.size());
    assertThat(selected).isEmpty();
  }
}
