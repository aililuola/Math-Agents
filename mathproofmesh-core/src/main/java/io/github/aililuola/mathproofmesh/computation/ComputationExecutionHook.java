package io.github.aililuola.mathproofmesh.computation;

@FunctionalInterface
public interface ComputationExecutionHook {
  void onPoint(ComputationExecutionFailurePoint point, String executionId);

  static ComputationExecutionHook noOp() {
    return (point, executionId) -> {};
  }
}
