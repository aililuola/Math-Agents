package io.github.aililuola.mathproofmesh.runstate;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

final class RunStateStatusDimensionTest {
  @Test
  void failedExecutionRetainsPartialMathematicsAndRecoverability() {
    RunStateSnapshot state =
        RunStateTestSupport.state(
            RunExecutionStatus.FAILED,
            RunStateTestSupport.partial(),
            RunStateTestSupport.usage(7, 70, 30),
            null,
            RunReportStatus.PARTIAL);
    assertThat(state.authority().executionStatus()).isEqualTo(RunExecutionStatus.FAILED);
    assertThat(state.authority().mathStatus()).isEqualTo(RunMathematicalStatus.PARTIAL_UNVERIFIED);
    assertThat(state.authority().usageStatus()).isEqualTo(RunUsageStatus.RECORDED);
    assertThat(state.authority().campaignStatus()).isEqualTo(RunCampaignStatus.RECOVERABLE);
    assertThat(state.projection().reportStatus()).isEqualTo(RunReportStatus.PARTIAL);
  }
}
