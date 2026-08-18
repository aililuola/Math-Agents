package io.github.aililuola.mathproofmesh.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.runstate.RunExecutionStatus;
import io.github.aililuola.mathproofmesh.runstate.RunStateSnapshot;
import io.github.aililuola.mathproofmesh.runstate.RunUsageSnapshot;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BackendRunStateCannotBypassReconcilerTest {
  @TempDir Path temporaryDirectory;

  @Test
  void backendSnapshotCannotChooseAuthoritySequenceOrVersion() {
    String problem = "Prove P.";
    RunStateSnapshot previous =
        RunStateApiGapTestSupport.state(
            problem,
            RunExecutionStatus.FAILED,
            RunStateApiGapTestSupport.partial(2, 1),
            RunUsageSnapshot.of(7, 70, 140, new BigDecimal("0.70"), 7, "", ""),
            null);
    RunStateSnapshot backend =
        RunStateApiGapTestSupport.state(
            problem,
            RunExecutionStatus.SUCCEEDED,
            RunStateApiGapTestSupport.partial(2, 1),
            RunUsageSnapshot.of(8, 80, 160, new BigDecimal("0.80"), 8, "", ""),
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

    assertThat(reconciled.authority().authoritySequence())
        .isEqualTo(previous.authority().authoritySequence() + 1);
    assertThat(reconciled.authority().version()).isEqualTo(previous.authority().version() + 1);
    assertThat(reconciled.stateHash()).isNotEqualTo(backend.stateHash());
  }
}
