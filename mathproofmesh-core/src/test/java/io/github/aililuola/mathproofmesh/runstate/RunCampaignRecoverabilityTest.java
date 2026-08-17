package io.github.aililuola.mathproofmesh.runstate;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

final class RunCampaignRecoverabilityTest {
  @Test
  void nonterminalCheckpointMakesFailedAttemptRecoverable() {
    assertThat(
            RunStateReconciler.deriveCampaign(
                RunExecutionStatus.FAILED,
                RunMathematicalStatus.PARTIAL_UNVERIFIED,
                true,
                false))
        .isEqualTo(RunCampaignStatus.RECOVERABLE);
  }
}
