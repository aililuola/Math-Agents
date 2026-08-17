package io.github.aililuola.mathproofmesh.runstate;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class RunStateTransitionLedger {
  private final List<RunStateTransition> transitions = new ArrayList<>();

  public synchronized RunStateTransition append(
      String runId,
      String fromStateHash,
      String toStateHash,
      RunStateTransitionTrigger trigger,
      Map<String, String> payload,
      Instant now) {
    long sequence = transitions.size();
    String id =
        "run-state-transition-"
            + CanonicalJson.stableHash(List.of(runId, sequence, toStateHash, trigger)).substring(0, 24);
    RunStateTransition transition =
        new RunStateTransition(
            id,
            runId,
            sequence,
            fromStateHash,
            toStateHash,
            trigger,
            payload,
            now);
    transitions.add(transition);
    return transition;
  }

  public synchronized RunStateTransitionSnapshot snapshot() {
    return new RunStateTransitionSnapshot(transitions, null);
  }

  public synchronized void restore(RunStateTransitionSnapshot snapshot) {
    transitions.clear();
    List<RunStateTransition> restored =
        Objects.requireNonNull(snapshot, "snapshot").transitions();
    for (int index = 0; index < restored.size(); index++) {
      if (restored.get(index).sequence() != index) {
        throw new IllegalArgumentException("run state transition sequence is not contiguous");
      }
    }
    transitions.addAll(restored);
  }
}
