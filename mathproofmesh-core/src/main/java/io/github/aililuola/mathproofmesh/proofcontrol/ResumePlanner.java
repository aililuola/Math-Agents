package io.github.aililuola.mathproofmesh.proofcontrol;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.ResumeDecision;
import io.github.aililuola.mathproofmesh.proofcontrol.ProofControlModels.ResumeDecisionKind;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Produces deterministic, side-effect-free resume decisions. */
public final class ResumePlanner {
  private final Map<String, ResumeDecision> decisionsByStateHash = new LinkedHashMap<>();

  public synchronized ResumeDecision plan(
      String runId,
      String persistedStateHash,
      boolean terminal,
      List<String> pendingActionIds,
      List<String> wakeableTaskIds,
      List<String> deferredTaskIds,
      boolean interventionRequired) {
    String requiredRunId = ProofControlModels.required(runId, "runId");
    String requiredStateHash =
        ProofControlModels.required(persistedStateHash, "persistedStateHash");
    ResumeDecision existing = decisionsByStateHash.get(requiredStateHash);
    if (existing != null) {
      return existing;
    }

    List<String> pending = sorted(pendingActionIds);
    List<String> wakeable = sorted(wakeableTaskIds);
    List<String> deferred = sorted(deferredTaskIds);
    ResumeDecisionKind kind;
    String interventionActionId = null;
    String reason;
    if (terminal) {
      kind = ResumeDecisionKind.NO_RESUMABLE_WORK;
      pending = List.of();
      wakeable = List.of();
      reason = "terminal run returns persisted result without provider calls";
    } else if (interventionRequired) {
      kind = ResumeDecisionKind.REOPEN_REQUIRED;
      interventionActionId =
          "resume_intervention_"
              + CanonicalJson.stableHash(
                      Map.of("run_id", requiredRunId, "state_hash", requiredStateHash))
                  .substring(0, 20);
      reason = "audited intervention is required before work can resume";
    } else if (!pending.isEmpty() || !wakeable.isEmpty()) {
      kind = ResumeDecisionKind.RESUME_WORK;
      reason = "persisted admitted work is resumable";
    } else {
      kind = ResumeDecisionKind.NO_RESUMABLE_WORK;
      reason = deferred.isEmpty() ? "no persisted work remains" : "all remaining work is deferred";
    }
    String decisionId =
        "resume_"
            + CanonicalJson.stableHash(
                    Map.of(
                        "run_id", requiredRunId,
                        "state_hash", requiredStateHash,
                        "decision", kind.name(),
                        "pending", pending,
                        "wakeable", wakeable,
                        "deferred", deferred))
                .substring(0, 20);
    ResumeDecision decision =
        new ResumeDecision(
            decisionId,
            kind,
            requiredStateHash,
            pending,
            wakeable,
            deferred,
            interventionActionId,
            reason);
    decisionsByStateHash.put(requiredStateHash, decision);
    return decision;
  }

  public int decisionCount() {
    return decisionsByStateHash.size();
  }

  private static List<String> sorted(List<String> values) {
    List<String> result = new ArrayList<>(values == null ? List.of() : values);
    result.removeIf(value -> value == null || value.isBlank());
    result.replaceAll(String::strip);
    result.sort(Comparator.naturalOrder());
    return List.copyOf(result);
  }
}
