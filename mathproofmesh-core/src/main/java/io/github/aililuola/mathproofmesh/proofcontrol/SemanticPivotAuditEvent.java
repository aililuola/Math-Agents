package io.github.aililuola.mathproofmesh.proofcontrol;

public record SemanticPivotAuditEvent(
    String pivotId, PivotDeltaStatus fromStatus, PivotDeltaStatus toStatus, String detail, long version) {
  public SemanticPivotAuditEvent {
    pivotId = PivotValues.required(pivotId, "pivotId");
    toStatus = java.util.Objects.requireNonNull(toStatus, "toStatus");
    detail = PivotValues.required(detail, "detail");
    if (version < 0L) {
      throw new IllegalArgumentException("version must be nonnegative");
    }
  }
}
