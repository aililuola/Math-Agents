package io.github.aililuola.mathproofmesh.concurrency;

import java.util.List;

public record ResearchEpochSnapshot(List<ResearchEpochRecord> epochs, long version) {
  public ResearchEpochSnapshot {
    epochs = epochs == null ? List.of() : List.copyOf(epochs);
    if (version < 0L) {
      throw new IllegalArgumentException("version must be nonnegative");
    }
  }

  @Override
  public List<ResearchEpochRecord> epochs() {
    return List.copyOf(epochs);
  }

  public static ResearchEpochSnapshot empty() {
    return new ResearchEpochSnapshot(List.of(), 0L);
  }
}
