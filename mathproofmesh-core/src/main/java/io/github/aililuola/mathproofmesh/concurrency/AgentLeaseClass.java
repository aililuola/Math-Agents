package io.github.aililuola.mathproofmesh.concurrency;

public enum AgentLeaseClass {
  RESEARCH,
  ADVERSARIAL_REVIEW,
  ADJUDICATION,
  COORDINATION;

  public boolean usesReservedCoordinationCapacity() {
    return this == ADVERSARIAL_REVIEW || this == ADJUDICATION || this == COORDINATION;
  }
}
