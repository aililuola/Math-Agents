package io.github.aililuola.mathproofmesh.concurrency;

import java.util.List;

public record ResearchResultSnapshot(List<ResearchWorkResultArtifact> artifacts, long version) {
  public ResearchResultSnapshot {
    artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
    if (version < 0L) {
      throw new IllegalArgumentException("version must be nonnegative");
    }
  }

  @Override
  public List<ResearchWorkResultArtifact> artifacts() {
    return List.copyOf(artifacts);
  }

  public static ResearchResultSnapshot empty() {
    return new ResearchResultSnapshot(List.of(), 0L);
  }
}
