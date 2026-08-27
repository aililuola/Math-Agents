package io.github.aililuola.mathproofmesh.runstate;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

final class RunAuthorityProjectionSeparationTest {
  @Test
  void reportFailureDoesNotChangeVerifiedAuthority() {
    RunStateSnapshot state =
        RunStateTestSupport.state(
            RunExecutionStatus.SUCCEEDED,
            RunStateTestSupport.verified(),
            RunStateTestSupport.usage(3, 20, 10),
            null,
            RunReportStatus.PROJECTION_FAILED);
    assertThat(state.authority().mathStatus()).isEqualTo(RunMathematicalStatus.VERIFIED);
    assertThat(state.authority().campaignStatus()).isEqualTo(RunCampaignStatus.TERMINAL);
    assertThat(state.projection().reportStatus()).isEqualTo(RunReportStatus.PROJECTION_FAILED);
  }
}
