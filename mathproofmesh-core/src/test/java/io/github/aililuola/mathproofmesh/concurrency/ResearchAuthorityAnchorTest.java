package io.github.aililuola.mathproofmesh.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

final class ResearchAuthorityAnchorTest {
  @Test
  void hashBindsEveryAuthorityProjection() {
    ResearchAuthorityAnchor anchor = ConcurrencyTestFixtures.anchor();
    ResearchAuthorityAnchor changed =
        new ResearchAuthorityAnchor(
            anchor.problemHash(), anchor.rootGoalHash(), "changed", anchor.attemptArtifactLedgerHash(),
            anchor.claimLifecycleHash(), anchor.researchCheckpointHash(), anchor.proofGraphHash(),
            anchor.canonicalizationHash(), anchor.convergenceHash(), anchor.semanticPivotHash(),
            anchor.strategyPortfolioHash(), anchor.claimCourtHash(), anchor.brokerHash(),
            anchor.computationHash(), anchor.runAuthorityHash());
    assertThat(changed.stableHash()).isNotEqualTo(anchor.stableHash());
  }
}
