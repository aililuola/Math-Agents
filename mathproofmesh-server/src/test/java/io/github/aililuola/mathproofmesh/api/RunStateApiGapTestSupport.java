package io.github.aililuola.mathproofmesh.api;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
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
import java.time.Instant;
import java.util.List;

final class RunStateApiGapTestSupport {
  private RunStateApiGapTestSupport() {}

  static RunStateSnapshot state(
      String problem,
      RunExecutionStatus execution,
      RunMathematicalProgressSnapshot progress,
      RunUsageSnapshot usage,
      RunStateSnapshot previous) {
    return new RunStateReconciler()
        .reconcile(
            new RunStateEvidenceBundle(
                "gap-run",
                CanonicalJson.stableHash(problem),
                previous == null ? "attempt-backend" : "attempt-resume",
                execution,
                RunTerminalReason.NONE,
                "report",
                true,
                false,
                "structured/desktop-solve-state.json",
                "a".repeat(64),
                "b".repeat(64),
                progress,
                List.of(
                    RunUsageEvidence.aggregate(
                        RunUsageEvidenceSource.SEMANTIC_CHECKPOINT, usage, "checkpoint")),
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
                Instant.parse("2026-08-17T00:00:00Z")))
        .state();
  }

  static RunMathematicalProgressSnapshot partial(int verified, int refuted) {
    return new RunMathematicalProgressSnapshot(
        verified, refuted, 2, true, true, true, true, false, false,
        false, false, false, true);
  }

  static RunMathematicalProgressSnapshot verified() {
    return new RunMathematicalProgressSnapshot(
        3, 1, 0, true, true, true, true, true, true,
        true, true, true, true);
  }
}
