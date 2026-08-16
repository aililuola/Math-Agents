package io.github.aililuola.mathproofmesh.computation;

public enum ComputationExecutionStatus {
  PLANNED,
  ADMITTED,
  RUNNING,
  RESULT_DURABLE,
  VERIFICATION_DURABLE,
  PROJECTION_READY,
  AUTHORITY_MUTATION_DURABLE,
  AUTHORITY_APPLIED,
  REJECTED,
  DEFERRED,
  QUARANTINED,
  FAILED
}
