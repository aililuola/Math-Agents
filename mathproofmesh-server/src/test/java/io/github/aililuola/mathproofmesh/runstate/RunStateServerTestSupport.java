package io.github.aililuola.mathproofmesh.runstate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class RunStateServerTestSupport {
  private RunStateServerTestSupport() {}

  public static RunStateSnapshot state(
      String runId, RunExecutionStatus execution, boolean checkpoint) {
    return new RunStateReconciler()
        .reconcile(
            new RunStateEvidenceBundle(
                runId,
                "1".repeat(64),
                "attempt-one",
                execution,
                RunTerminalReason.NONE,
                "proof",
                checkpoint,
                false,
                checkpoint ? "structured/desktop-solve-state.json" : "",
                checkpoint ? "2".repeat(64) : "",
                "3".repeat(64),
                new RunMathematicalProgressSnapshot(
                    2, 1, 5, true, true, true, true, false, false,
                    false, false, false, true),
                List.of(
                    RunUsageEvidence.aggregate(
                        RunUsageEvidenceSource.SEMANTIC_CHECKPOINT,
                        RunUsageSnapshot.of(
                            7, 100, 200, new BigDecimal("0.5"), 12.0, "", ""),
                        "checkpoint")),
                null,
                RunProjectionSnapshot.absent(""),
                Instant.parse("2026-08-17T00:00:00Z")))
        .state();
  }
}
