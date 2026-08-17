package io.github.aililuola.mathproofmesh.desktop;

import io.github.aililuola.mathproofmesh.runstate.RunExecutionStatus;
import io.github.aililuola.mathproofmesh.runstate.RunMathematicalProgressSnapshot;
import io.github.aililuola.mathproofmesh.runstate.RunProjectionSnapshot;
import io.github.aililuola.mathproofmesh.runstate.RunReportStatus;
import io.github.aililuola.mathproofmesh.runstate.RunStateEvidenceBundle;
import io.github.aililuola.mathproofmesh.runstate.RunStateReconciler;
import io.github.aililuola.mathproofmesh.runstate.RunStateSnapshot;
import io.github.aililuola.mathproofmesh.runstate.RunTerminalReason;
import io.github.aililuola.mathproofmesh.runstate.RunUsageEvidence;
import io.github.aililuola.mathproofmesh.runstate.RunUsageEvidenceSource;
import io.github.aililuola.mathproofmesh.runstate.RunUsageSnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

final class DesktopRunStateTestSupport {
  private DesktopRunStateTestSupport() {}

  static RunStateSnapshot failure(String runId, RunStateSnapshot previous, long calls) {
    return state(runId, previous, RunExecutionStatus.FAILED, calls, false, false);
  }

  static RunStateSnapshot state(
      String runId,
      RunStateSnapshot previous,
      RunExecutionStatus execution,
      long calls,
      boolean finalProof,
      boolean verified) {
    return stateWithAttempt(
        runId,
        previous,
        execution,
        calls,
        finalProof,
        verified,
        "attempt-" + (previous == null ? 0 : previous.authority().authoritySequence() + 1));
  }

  static RunStateSnapshot stateWithAttempt(
      String runId,
      RunStateSnapshot previous,
      RunExecutionStatus execution,
      long calls,
      boolean finalProof,
      boolean verified,
      String attemptId) {
    long input = Math.multiplyExact(calls, 10L);
    long output = Math.multiplyExact(calls, 20L);
    return new RunStateReconciler()
        .reconcile(
            new RunStateEvidenceBundle(
                runId,
                "a".repeat(64),
                attemptId,
                execution,
                RunTerminalReason.NONE,
                verified ? "report" : "proof",
                true,
                verified,
                "structured/desktop-solve-state.json",
                "b".repeat(64),
                "c".repeat(64),
                new RunMathematicalProgressSnapshot(
                    verified ? 3 : 2,
                    1,
                    verified ? 0 : 5,
                    true,
                    true,
                    true,
                    true,
                    false,
                    false,
                    finalProof,
                    verified,
                    verified,
                    true),
                List.of(
                    RunUsageEvidence.aggregate(
                        RunUsageEvidenceSource.SEMANTIC_CHECKPOINT,
                        RunUsageSnapshot.of(
                            calls,
                            input,
                            output,
                            new BigDecimal(calls).movePointLeft(2),
                            calls,
                            "",
                            ""),
                        "checkpoint-" + calls)),
                previous,
                new RunProjectionSnapshot(
                    previous == null ? "" : previous.authority().authorityHash(),
                    RunReportStatus.PARTIAL,
                    "",
                    "",
                    "",
                    "",
                    "",
                    "",
                    0,
                    List.of(),
                    null),
                Instant.now()))
        .state();
  }
}
