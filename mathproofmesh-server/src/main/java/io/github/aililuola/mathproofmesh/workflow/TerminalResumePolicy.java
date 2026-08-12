package io.github.aililuola.mathproofmesh.workflow;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.util.List;

/** Deterministic terminal-resume gate that protects the zero-provider-call path. */
public final class TerminalResumePolicy {
  public ResumeDecision decide(ResumeRequest request) {
    java.util.Objects.requireNonNull(request, "request");
    if (request.hardStopped()
        && !request.pendingTask()
        && !request.configChanged()
        && !request.reopenWithPivot()) {
      return new ResumeDecision(
          false,
          0,
          "",
          "terminal run has no new work",
          CanonicalJson.stableHash(List.of(request.runId(), "terminal-zero-work")));
    }
    String intervention =
        request.reopenWithPivot()
            ? "pivot"
            : request.configChanged() ? "configuration" : "pending-task";
    return new ResumeDecision(
        true,
        request.providerCallsAllowed(),
        intervention,
        "auditable new work permits resume",
        CanonicalJson.stableHash(List.of(request.runId(), intervention)));
  }

  public record ResumeRequest(
      String runId,
      boolean hardStopped,
      boolean pendingTask,
      boolean configChanged,
      boolean reopenWithPivot,
      int providerCallsAllowed) {
    public ResumeRequest {
      runId = runId == null ? "" : runId.strip();
      if (runId.isEmpty() || providerCallsAllowed < 0) {
        throw new IllegalArgumentException("invalid resume request");
      }
    }
  }

  public record ResumeDecision(
      boolean resume,
      int providerCalls,
      String intervention,
      String reason,
      String actionKey) {}
}
