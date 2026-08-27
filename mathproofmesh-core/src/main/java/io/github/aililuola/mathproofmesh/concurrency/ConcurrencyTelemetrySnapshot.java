package io.github.aililuola.mathproofmesh.concurrency;

import java.util.List;

public record ConcurrencyTelemetrySnapshot(List<ConcurrencyTelemetryEvent> events, long version) {
  public ConcurrencyTelemetrySnapshot {
    events = events == null ? List.of() : List.copyOf(events);
    if (version < 0L) {
      throw new IllegalArgumentException("version must be nonnegative");
    }
  }

  @Override
  public List<ConcurrencyTelemetryEvent> events() {
    return List.copyOf(events);
  }

  public static ConcurrencyTelemetrySnapshot empty() {
    return new ConcurrencyTelemetrySnapshot(List.of(), 0L);
  }
}
