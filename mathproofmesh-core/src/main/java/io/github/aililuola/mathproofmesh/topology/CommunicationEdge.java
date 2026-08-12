package io.github.aililuola.mathproofmesh.topology;

public record CommunicationEdge(
    String source,
    String target,
    String stage,
    String payloadType,
    String reason,
    boolean rawEvidenceIncluded) {

  public CommunicationEdge {
    source = required("source", source);
    target = required("target", target);
    stage = required("stage", stage);
    payloadType = required("payloadType", payloadType);
    reason = reason == null ? "" : reason;
  }

  private static String required(String name, String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value;
  }
}
