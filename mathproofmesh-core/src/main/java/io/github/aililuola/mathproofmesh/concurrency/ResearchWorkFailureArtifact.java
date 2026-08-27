package io.github.aililuola.mathproofmesh.concurrency;

import java.util.Objects;

public record ResearchWorkFailureArtifact(
    String workItemId, String epochId, String failureCode, String publicDetail) {
  public ResearchWorkFailureArtifact {
    workItemId = Objects.requireNonNull(workItemId, "workItemId").strip();
    epochId = Objects.requireNonNull(epochId, "epochId").strip();
    failureCode = Objects.requireNonNull(failureCode, "failureCode").strip();
    publicDetail = publicDetail == null ? "" : publicDetail.strip();
  }
}
