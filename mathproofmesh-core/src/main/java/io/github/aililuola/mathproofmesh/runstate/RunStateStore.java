package io.github.aililuola.mathproofmesh.runstate;

import java.util.List;
import java.util.Optional;

public interface RunStateStore {
  Optional<RunStateSnapshot> load(String runId);

  RunStateSnapshot compareAndSet(
      String runId,
      long expectedVersion,
      RunStateSnapshot next,
      String ownerId,
      long fencingToken);

  RunStateSnapshot compareAndSetProjection(
      String runId,
      String expectedStateHash,
      long expectedProjectionVersion,
      RunStateSnapshot next,
      String ownerId,
      long fencingToken);

  List<RunStateTransition> transitions(String runId);
}
