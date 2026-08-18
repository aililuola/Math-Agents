package io.github.aililuola.mathproofmesh.runstate;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

final class RunMathematicalProgressSnapshotMonotonicityTest {
  @Test
  void missingLaterEvidenceCannotEraseFinalProofAuthority() {
    RunStateSnapshot verified =
        RunStateTestSupport.state(
            RunExecutionStatus.SUCCEEDED,
            RunStateTestSupport.verified(),
            RunStateTestSupport.usage(2, 20, 40),
            null,
            RunReportStatus.FINAL);

    RunStateSnapshot sparse =
        RunStateTestSupport.state(
            RunExecutionStatus.SUCCEEDED,
            RunMathematicalProgressSnapshot.empty(),
            RunStateTestSupport.usage(2, 20, 40),
            verified,
            RunReportStatus.FINAL);

    assertThat(sparse.authority().mathematicalProgress().finalProofPresent()).isTrue();
    assertThat(sparse.authority().mathematicalProgress().finalValidationPassed()).isTrue();
    assertThat(sparse.authority().mathematicalProgress().finalReviewPassed()).isTrue();
  }
}
