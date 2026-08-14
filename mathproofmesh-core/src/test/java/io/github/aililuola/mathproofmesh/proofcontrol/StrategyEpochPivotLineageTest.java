package io.github.aililuola.mathproofmesh.proofcontrol;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StrategyEpochPivotLineageTest {
  @Test
  void semanticPivotCreatesANewRootEpochWhileKeepingTheOldRoot() {
    StrategyArchive archive = new StrategyArchive();
    archive.archive(
        control(SemanticPivotTestFixtures.sourceStrategy()), "artifact://source", 0);
    archive.archivePivotEpoch(
        control(SemanticPivotTestFixtures.proposedStrategy()),
        SemanticPivotTestFixtures.SOURCE_ID,
        SemanticPivotTestFixtures.validDelta().pivotId(),
        4);

    assertThat(archive.originalRetained(SemanticPivotTestFixtures.SOURCE_ID)).isTrue();
    assertThat(archive.originalRetained(SemanticPivotTestFixtures.PROPOSED_ID)).isTrue();
    assertThat(archive.lineage().get(SemanticPivotTestFixtures.PROPOSED_ID).parentStrategyId())
        .isNull();
  }

  private static ProofControlModels.Strategy control(
      io.github.aililuola.mathproofmesh.contract.StrategyCard strategy) {
    return new ProofControlModels.Strategy(
        strategy.strategyId(),
        strategy.title(),
        strategy.coreIdea(),
        strategy.prerequisites(),
        java.util.List.of(strategy.bottleneck()),
        strategy.expectedLemmas(),
        java.util.List.of(strategy.falsificationTest()),
        ProofIdentity.domainObjects(
            java.util.List.of(strategy.coreIdea(), strategy.bottleneck())),
        "route-1");
  }
}
