package io.github.aililuola.mathproofmesh.computation;

@FunctionalInterface
public interface ComputationStatePersister {
  void persist(String reason, ComputationExecutionState state);

  static ComputationStatePersister noOp() {
    return (reason, state) -> {};
  }
}
