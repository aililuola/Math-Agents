package io.github.aililuola.mathproofmesh.orchestration;

/** Atomic admission result for a mathematical exploration signature. */
public record ExplorationAdmission(
    boolean accepted,
    String leaseId,
    ExplorationModel tier,
    int reservedCalls,
    String reason) {
  public ExplorationAdmission {
    leaseId = leaseId == null ? "" : leaseId.strip();
    reason = reason == null ? "" : reason.strip();
    if (reservedCalls < 0) {
      throw new IllegalArgumentException("reservedCalls must be nonnegative");
    }
  }
}
