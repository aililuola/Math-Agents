package io.github.aililuola.mathproofmesh.computation;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Run-local quota ledger; no state is shared between broker instances or tenants. */
public final class ComputationLedger {
  private final ConcurrentMap<String, Usage> usageByPath = new ConcurrentHashMap<>();

  public Usage usage(String pathId) {
    return usageByPath.getOrDefault(pathId, new Usage(0, 0.0));
  }

  public void record(String pathId, double cpuSeconds) {
    usageByPath.compute(
        pathId,
        (ignored, current) -> {
          Usage existing = current == null ? new Usage(0, 0.0) : current;
          return new Usage(existing.experiments + 1, existing.cpuSeconds + cpuSeconds);
        });
  }

  public record Usage(int experiments, double cpuSeconds) {
    public Usage {
      if (experiments < 0 || cpuSeconds < 0) {
        throw new IllegalArgumentException("ledger usage must be nonnegative");
      }
    }
  }
}
