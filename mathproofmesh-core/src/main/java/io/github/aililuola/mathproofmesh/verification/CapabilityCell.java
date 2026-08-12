package io.github.aililuola.mathproofmesh.verification;

/** Empirical trust cell indexed by agent, mathematical domain, and role. */
public record CapabilityCell(
    String agentId,
    String domain,
    String role,
    int observations,
    double weightedSuccess,
    double weightedTotal,
    int overturns,
    double score) {

  public CapabilityCell {
    agentId = require(agentId, "agentId");
    domain = require(domain, "domain");
    role = require(role, "role");
    if (observations < 0
        || weightedSuccess < 0.0
        || weightedTotal < 0.0
        || overturns < 0
        || score < 0.0
        || score > 1.0) {
      throw new IllegalArgumentException("capability cell values are invalid");
    }
  }

  public static CapabilityCell initial(String agentId, String domain, String role) {
    return new CapabilityCell(agentId, domain, role, 0, 0.0, 0.0, 0, 0.5);
  }

  private static String require(String value, String field) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return normalized;
  }
}
