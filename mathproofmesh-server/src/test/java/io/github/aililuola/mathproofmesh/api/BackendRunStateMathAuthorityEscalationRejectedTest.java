package io.github.aililuola.mathproofmesh.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.runstate.RunExecutionStatus;
import io.github.aililuola.mathproofmesh.runstate.RunMathematicalStatus;
import io.github.aililuola.mathproofmesh.runstate.RunStateSnapshot;
import io.github.aililuola.mathproofmesh.runstate.RunUsageSnapshot;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BackendRunStateMathAuthorityEscalationRejectedTest {
  @TempDir Path temporaryDirectory;

  @Test
  void backendSnapshotCannotClaimVerifiedWithoutDurableProofEvidence() {
    String problem = "Prove P.";
    RunUsageSnapshot usage =
        RunUsageSnapshot.of(7, 70, 140, new BigDecimal("0.70"), 7, "", "");
    RunStateSnapshot previous =
        RunStateApiGapTestSupport.state(
            problem,
            RunExecutionStatus.FAILED,
            RunStateApiGapTestSupport.partial(2, 1),
            usage,
            null);
    RunStateSnapshot backend =
        RunStateApiGapTestSupport.state(
            problem,
            RunExecutionStatus.SUCCEEDED,
            RunStateApiGapTestSupport.verified(),
            usage,
            null);
    RunExecutionBackend.RunExecutionResult result =
        new RunExecutionBackend.RunExecutionResult(
            "completed", "report", "backend", List.of(), List.of(), "", 1,
            RunExecutionBackend.ExecutionUsage.zero(), backend);

    RunStateSnapshot reconciled =
        RunStateApiProjection.reconcile(
            new SolveRequest(problem, "gap-run", null, "smoke"),
            "gap-run",
            "attempt-reconciled",
            temporaryDirectory,
            result,
            previous);

    assertThat(reconciled.authority().mathStatus())
        .isNotEqualTo(RunMathematicalStatus.VERIFIED);
  }
}
