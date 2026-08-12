package io.github.aililuola.mathproofmesh.orchestration;

/** Independent review evidence; a partial or rejected delta never becomes global evidence. */
public record RouteReviewEvidence(
    String routeId,
    String authorAgentId,
    String reviewerAgentId,
    boolean accepted,
    boolean deltaRejected,
    double confidence,
    String reason) {
  public RouteReviewEvidence {
    routeId = required(routeId, "routeId");
    authorAgentId = required(authorAgentId, "authorAgentId");
    reviewerAgentId = required(reviewerAgentId, "reviewerAgentId");
    reason = reason == null ? "" : reason.strip();
    if (authorAgentId.equals(reviewerAgentId)) {
      throw new IllegalArgumentException("route author cannot review its own delta");
    }
    if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
      throw new IllegalArgumentException("confidence must be in [0,1]");
    }
  }

  public boolean globallyShareable() {
    return accepted && !deltaRejected;
  }

  private static String required(String value, String field) {
    String result = value == null ? "" : value.strip();
    if (result.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return result;
  }
}
