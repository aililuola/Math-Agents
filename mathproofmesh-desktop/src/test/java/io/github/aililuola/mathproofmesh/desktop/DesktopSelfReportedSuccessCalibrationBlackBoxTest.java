package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.memory.TypedMemory;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DesktopSelfReportedSuccessCalibrationBlackBoxTest {
  @Test
  void modelPriorCannotOverrideTrustedClaimEvidence() {
    TypedMemory memory =
        StrategyDiversityIssue007BlackBoxSupport.memoryWithVerifiedCounterexample();
    StrategyCard strategyA =
        StrategyDiversityIssue007BlackBoxSupport.strategy(
            "strategy-a",
            "Model favorite",
            "Hamiltonian shortcut",
            StrategyDiversityIssue007BlackBoxSupport.REFUTED_CLAIM,
            0.99d,
            "model-favorite");
    StrategyCard strategyB =
        StrategyDiversityIssue007BlackBoxSupport.strategy(
            "strategy-b",
            "Evidence-grounded route",
            "Longest-path extremal argument",
            "Every longest path in a finite tree has leaf endpoints.",
            0.55d,
            "verified-route");

    StrategyCard first =
        StrategyDiversityIssue007BlackBoxSupport.selectWithIssue007Pipeline(
                List.of(strategyA, strategyB),
                1,
                memory,
                Set.of("Every longest path in a finite tree has leaf endpoints."))
            .getFirst();

    assertThat(memory.negativeKnowledgeRegistry().records()).hasSize(1);
    System.out.println("MODEL_PRIOR_A=0.99");
    System.out.println("MODEL_PRIOR_B=0.55");
    System.out.println("EXPECTED_FIRST_SELECTED=B");
    System.out.println("ACTUAL_FIRST_SELECTED=" + first.strategyId().substring(9).toUpperCase());
    assertThat(first.strategyId()).isEqualTo("strategy-b");
  }
}
