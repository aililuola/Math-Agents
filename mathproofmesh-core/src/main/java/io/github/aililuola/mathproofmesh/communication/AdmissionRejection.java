package io.github.aililuola.mathproofmesh.communication;

public enum AdmissionRejection {
  SCHEMA_OR_LENGTH(1),
  PROBLEM_HASH(2),
  SOURCE_OWNERSHIP(3),
  TARGET_ROUTE(4),
  TTL(5),
  ARTIFACT_REFERENCE(6),
  QUANTIFIER_SCOPE(7),
  DEPENDENCY(8),
  EVIDENCE_TIER(9),
  REVIEW_INDEPENDENCE(10),
  CONTENT_HASH(11),
  CONTENT_DUPLICATE(12),
  CAPACITY(13),
  PERSISTENCE(14);

  private final int gate;

  AdmissionRejection(int gate) {
    this.gate = gate;
  }

  public int gate() {
    return gate;
  }
}
