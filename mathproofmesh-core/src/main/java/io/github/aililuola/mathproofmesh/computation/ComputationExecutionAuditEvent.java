package io.github.aililuola.mathproofmesh.computation;

public record ComputationExecutionAuditEvent(
    int sequence, ComputationExecutionStatus status, int round, String code) {
  public ComputationExecutionAuditEvent {
    if (sequence < 1 || round < 0 || status == null) {
      throw new IllegalArgumentException("invalid computation execution audit event");
    }
    code = code == null ? "" : code.strip();
  }
}
