package io.github.aililuola.mathproofmesh.concurrency;

import java.util.List;

public record ResearchTaskSnapshot(List<ResearchWorkRecord> tasks, long version) {
  public ResearchTaskSnapshot {
    tasks = tasks == null ? List.of() : List.copyOf(tasks);
    if (version < 0L) {
      throw new IllegalArgumentException("version must be nonnegative");
    }
  }

  @Override
  public List<ResearchWorkRecord> tasks() {
    return List.copyOf(tasks);
  }

  public static ResearchTaskSnapshot empty() {
    return new ResearchTaskSnapshot(List.of(), 0L);
  }
}
