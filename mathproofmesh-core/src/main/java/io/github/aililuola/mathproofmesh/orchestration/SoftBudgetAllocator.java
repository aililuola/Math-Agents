package io.github.aililuola.mathproofmesh.orchestration;

import java.util.LinkedHashMap;
import java.util.Map;

/** Allocates soft breadth/depth/verification/synthesis shares while protecting finish calls. */
public final class SoftBudgetAllocator {
  public Allocation allocate(int remainingCalls, int finishReserve, Shares shares) {
    if (remainingCalls < 0 || finishReserve < 0 || finishReserve > remainingCalls) {
      throw new IllegalArgumentException("invalid call budget");
    }
    Shares effective = shares == null ? Shares.defaults() : shares;
    int available = remainingCalls - finishReserve;
    Map<String, Integer> calls = new LinkedHashMap<>();
    calls.put("breadth", (int) Math.floor(available * effective.breadth()));
    calls.put("depth", (int) Math.floor(available * effective.depth()));
    calls.put("verification", (int) Math.floor(available * effective.verification()));
    int used = calls.values().stream().mapToInt(Integer::intValue).sum();
    calls.put("synthesis", Math.max(0, available - used));
    return new Allocation(Map.copyOf(calls), finishReserve, available);
  }

  public record Shares(
      double breadth, double depth, double verification, double synthesis) {
    public Shares {
      double total = breadth + depth + verification + synthesis;
      if (!Double.isFinite(total)
          || breadth < 0
          || depth < 0
          || verification < 0
          || synthesis < 0
          || Math.abs(total - 1.0d) > 0.000001d) {
        throw new IllegalArgumentException("budget shares must be nonnegative and sum to one");
      }
    }

    public static Shares defaults() {
      return new Shares(0.25d, 0.35d, 0.25d, 0.15d);
    }
  }

  public record Allocation(Map<String, Integer> calls, int finishReserve, int schedulableCalls) {
    public Allocation {
      calls = Map.copyOf(calls);
    }

    public Map<String, Integer> calls() {
      return Map.copyOf(calls);
    }
  }
}
