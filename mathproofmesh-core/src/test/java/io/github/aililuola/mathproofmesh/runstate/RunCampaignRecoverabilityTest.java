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

  @Test
  void successfulAttemptWithoutVerifiedMathematicsKeepsCampaignRecoverable() {
    assertThat(
            RunStateReconciler.deriveCampaign(
                RunExecutionStatus.SUCCEEDED,
                RunMathematicalStatus.PARTIAL_UNVERIFIED,
                false,
                false))
        .isEqualTo(RunCampaignStatus.RECOVERABLE);
  }

  @Test
  void failedAttemptBeforeTheFirstCheckpointCanBeRetried() {
    assertThat(
            RunStateReconciler.deriveCampaign(
                RunExecutionStatus.FAILED,
                RunMathematicalStatus.NOT_STARTED,
                false,
                false))
        .isEqualTo(RunCampaignStatus.RECOVERABLE);
  }
}
