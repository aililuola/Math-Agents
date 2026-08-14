package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.List;
import java.util.Map;

public record PivotDeltaAudit(
    String pivotId,
    PivotDeltaStatus status,
    PivotStructuralSignature sourceSignature,
    PivotStructuralSignature proposedSignature,
    List<String> failureCodes,
    Map<String, String> details) {
  public PivotDeltaAudit {
    pivotId = PivotValues.required(pivotId, "pivotId");
    status = java.util.Objects.requireNonNull(status, "status");
    sourceSignature = java.util.Objects.requireNonNull(sourceSignature, "sourceSignature");
    proposedSignature = java.util.Objects.requireNonNull(proposedSignature, "proposedSignature");
    failureCodes = PivotValues.copy(failureCodes);
    details = details == null ? Map.of() : Map.copyOf(details);
  }

  public boolean passed() {
    return failureCodes.isEmpty() && status == PivotDeltaStatus.AWAITING_REVIEW;
  }

  @Override
  public List<String> failureCodes() {
    return List.copyOf(failureCodes);
  }

  @Override
  public Map<String, String> details() {
    return Map.copyOf(details);
  }
}
