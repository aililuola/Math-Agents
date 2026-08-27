package io.github.aililuola.mathproofmesh.concurrency;

import java.util.Objects;

public record ResearchWorkRecord(
    ResearchWorkItem item,
    ResearchWorkStatus status,
    String agentId,
    String providerRequestId,
    String resultRef,
    String resultHash,
    long version) {
  public ResearchWorkRecord {
    item = Objects.requireNonNull(item, "item");
    status = Objects.requireNonNull(status, "status");
    agentId = optional(agentId);
    providerRequestId = optional(providerRequestId);
    resultRef = optional(resultRef);
    resultHash = optional(resultHash);
    if (version < 1L) {
      throw new IllegalArgumentException("version must be positive");
    }
  }

  public ResearchWorkRecord transition(
      ResearchWorkStatus next,
      String nextAgentId,
      String nextProviderRequestId,
      String nextResultRef,
      String nextResultHash) {
    if (status == ResearchWorkStatus.MERGED || status == ResearchWorkStatus.SUPERSEDED) {
      return this;
    }
    return new ResearchWorkRecord(
        item,
        Objects.requireNonNull(next, "next"),
        nextAgentId == null ? agentId : nextAgentId,
        nextProviderRequestId == null ? providerRequestId : nextProviderRequestId,
        nextResultRef == null ? resultRef : nextResultRef,
        nextResultHash == null ? resultHash : nextResultHash,
        version + 1L);
  }

  private static String optional(String value) {
    return value == null ? "" : value.strip();
  }
}
