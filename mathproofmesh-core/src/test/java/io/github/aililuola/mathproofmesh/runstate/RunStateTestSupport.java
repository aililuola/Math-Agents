package io.github.aililuola.mathproofmesh.runstate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

final class RunStateTestSupport {
  private RunStateTestSupport() {}

  static RunMathematicalProgressSnapshot partial() {
    return new RunMathematicalProgressSnapshot(
        2, 1, 5, true, true, true, true, false, true, false, false, false, true);
  }

  static RunMathematicalProgressSnapshot verified() {
    return new RunMathematicalProgressSnapshot(
        2, 1, 0, true, true, true, true, true, true, true, true, true, true);
  }

  static RunUsageSnapshot usage(long calls, long input, long output) {
    return RunUsageSnapshot.of(
        calls, input, output, new BigDecimal("1.25"), 100.0d, "requests", "artifacts");
  }

  static RunStateSnapshot state(
      RunExecutionStatus execution,
      RunMathematicalProgressSnapshot progress,
      RunUsageSnapshot usage,
      RunStateSnapshot previous,
      RunReportStatus report) {
    RunProjectionSnapshot projection =
        new RunProjectionSnapshot(
            previous == null ? "" : previous.authority().authorityHash(),
            report,
            "",
            "",
            "",
            "",
            "",
            "",
            0L,
            List.of(),
            null);
    return new RunStateReconciler()
        .reconcile(
            new RunStateEvidenceBundle(
                "run-1",
                "a".repeat(64),
                previous == null ? "attempt-1" : "attempt-2",
                execution,
                RunTerminalReason.NONE,
                "route_team",
                true,
                false,
                "structured/desktop-solve-state.json",
                "b".repeat(64),
                "c".repeat(64),
                progress,
                List.of(
                    RunUsageEvidence.aggregate(
                        RunUsageEvidenceSource.SEMANTIC_CHECKPOINT, usage, "checkpoint")),
                previous,
                projection,
                Instant.parse("2026-01-01T00:00:00Z")))
        .state();
  }

  static RunStateSnapshot stateWithProofGraph(
      RunMathematicalProgressSnapshot progress,
      RunStateSnapshot previous,
      String proofGraphHash) {
    return new RunStateReconciler()
        .reconcile(
            new RunStateEvidenceBundle(
                "run-1",
                "a".repeat(64),
                previous == null ? "attempt-1" : "attempt-2",
                RunExecutionStatus.RUNNING,
                RunTerminalReason.NONE,
                "proof",
                true,
                false,
                "structured/desktop-solve-state.json",
                "b".repeat(64),
                proofGraphHash,
                progress,
                List.of(),
                previous,
                previous == null
                    ? RunProjectionSnapshot.absent("")
                    : previous.projection(),
                Instant.parse("2026-01-01T00:00:00Z")))
        .state();
  }
}
