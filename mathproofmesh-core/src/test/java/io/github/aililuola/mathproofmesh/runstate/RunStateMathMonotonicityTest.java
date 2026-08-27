package io.github.aililuola.mathproofmesh.runstate;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

final class RunStateMathMonotonicityTest {
  @Test
  void verifiedMathematicsDoesNotRegressOnExecutionFailure() {
    RunStateSnapshot verified =
        RunStateTestSupport.state(
            RunExecutionStatus.SUCCEEDED,
            RunStateTestSupport.verified(),
            RunStateTestSupport.usage(2, 10, 10),
            null,
            RunReportStatus.FINAL);
    RunStateSnapshot failed =
        RunStateTestSupport.state(
            RunExecutionStatus.FAILED,
            RunStateTestSupport.partial(),
            RunStateTestSupport.usage(2, 10, 10),
            verified,
            RunReportStatus.PROJECTION_FAILED);
    assertThat(failed.authority().mathStatus()).isEqualTo(RunMathematicalStatus.VERIFIED);
  }
}
