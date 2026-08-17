package io.github.aililuola.mathproofmesh.runstate;

import java.util.Comparator;
import java.util.List;

public record RunStateTransitionSnapshot(
    List<RunStateTransition> transitions, String stableHash) {
  public RunStateTransitionSnapshot {
    transitions =
        transitions == null
            ? List.of()
            : transitions.stream().sorted(Comparator.comparingLong(RunStateTransition::sequence)).toList();
    stableHash = RunStateHashes.generatedOrVerified(stableHash, transitions, "transition ledger");
  }

  public static RunStateTransitionSnapshot empty() {
    return new RunStateTransitionSnapshot(List.of(), null);
  }

  @Override
  public List<RunStateTransition> transitions() {
    return List.copyOf(transitions);
  }
}
