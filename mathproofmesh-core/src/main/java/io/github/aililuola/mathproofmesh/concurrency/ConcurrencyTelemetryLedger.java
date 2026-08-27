package io.github.aililuola.mathproofmesh.concurrency;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

public final class ConcurrencyTelemetryLedger {
  private final LongSupplier ticker;
  private final List<ConcurrencyTelemetryEvent> events = new ArrayList<>();
  private long version;

  public ConcurrencyTelemetryLedger() {
    this(System::nanoTime);
  }

  public ConcurrencyTelemetryLedger(LongSupplier ticker) {
    this.ticker = Objects.requireNonNull(ticker, "ticker");
  }

  public synchronized ConcurrencyTelemetryEvent record(
      ConcurrencyEventType type,
      String epochId,
      String workItemId,
      String agentId,
      int readyWorkCount) {
    long sequence = ++version;
    ConcurrencyTelemetryEvent event =
        new ConcurrencyTelemetryEvent(
            sequence,
            Math.max(0L, ticker.getAsLong()),
            type,
            epochId,
            workItemId,
            agentId,
            readyWorkCount);
    events.add(event);
    return event;
  }

  public synchronized ConcurrencyTelemetrySnapshot snapshot() {
    return new ConcurrencyTelemetrySnapshot(List.copyOf(events), version);
  }

  public synchronized void restore(ConcurrencyTelemetrySnapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot");
    events.clear();
    events.addAll(snapshot.events());
    version = snapshot.version();
  }

  public synchronized ConcurrencyMetrics metrics(int researchSlots, int totalSlots) {
    if (researchSlots < 1 || totalSlots < researchSlots) {
      throw new IllegalArgumentException("slot counts are invalid");
    }
    List<ConcurrencyTelemetryEvent> ordered =
        events.stream()
            .sorted(
                Comparator.comparingLong(ConcurrencyTelemetryEvent::monotonicNanos)
                    .thenComparingLong(ConcurrencyTelemetryEvent::sequence))
            .toList();
    if (ordered.size() < 2) {
      return new ConcurrencyMetrics(
          0.0d, 0.0d, 0.0d, 0.0d, 0.0d, 0.0d, 0.0d, 0, Map.of(), Map.of(), 0L, 0L, 0L);
    }
    Map<String, Long> activeStarts = new HashMap<>();
    Map<String, Long> busy = new LinkedHashMap<>();
    Map<String, Long> leaseCounts = new LinkedHashMap<>();
    long areaWhole = 0L;
    long areaReady = 0L;
    long durationReady = 0L;
    long singleReady = 0L;
    long zeroReady = 0L;
    long activeWindowStart = -1L;
    long activeWindowEnd = -1L;
    long queueWait = 0L;
    long barrierWait = 0L;
    long barrierStart = -1L;
    long idleSlotsReady = 0L;
    int active = 0;
    int ready = 0;
    int maximum = 0;
    long previous = ordered.getFirst().monotonicNanos();
    for (ConcurrencyTelemetryEvent event : ordered) {
      long now = event.monotonicNanos();
      long elapsed = Math.max(0L, now - previous);
      areaWhole += elapsed * active;
      if (ready > 0) {
        durationReady += elapsed;
        areaReady += elapsed * active;
        idleSlotsReady += elapsed * Math.max(0, researchSlots - active);
        if (active == 1) {
          singleReady += elapsed;
        } else if (active == 0) {
          zeroReady += elapsed;
        }
      }
      previous = now;
      ready = event.readyWorkCount();
      switch (event.type()) {
        case LEASE_ACQUIRED -> leaseCounts.merge(event.agentId(), 1L, Long::sum);
        case PROVIDER_CALL_STARTED -> {
          active++;
          maximum = Math.max(maximum, active);
          activeStarts.put(event.workItemId(), now);
          if (activeWindowStart < 0L) {
            activeWindowStart = now;
          }
        }
        case PROVIDER_CALL_COMPLETED -> {
          active = Math.max(0, active - 1);
          Long started = activeStarts.remove(event.workItemId());
          if (started != null) {
            busy.merge(event.agentId(), Math.max(0L, now - started), Long::sum);
          }
          activeWindowEnd = now;
        }
        case WORK_QUEUED -> queueWait += 0L;
        case BARRIER_ENTERED -> barrierStart = now;
        case BARRIER_RELEASED -> {
          if (barrierStart >= 0L) {
            barrierWait += Math.max(0L, now - barrierStart);
            barrierStart = -1L;
          }
        }
        default -> {
          // Other events define durable boundaries but do not change call concurrency.
        }
      }
    }
    long wholeDuration =
        ordered.getLast().monotonicNanos() - ordered.getFirst().monotonicNanos();
    long activeDuration =
        activeWindowStart < 0L || activeWindowEnd < activeWindowStart
            ? 0L
            : activeWindowEnd - activeWindowStart;
    return new ConcurrencyMetrics(
        divide(areaWhole, wholeDuration),
        divide(areaWhole, activeDuration),
        divide(areaReady, durationReady),
        divide(areaReady, durationReady * researchSlots),
        divide(areaReady, durationReady * totalSlots),
        divide(singleReady, durationReady),
        divide(zeroReady, durationReady),
        maximum,
        busy,
        leaseCounts,
        queueWait,
        barrierWait,
        idleSlotsReady);
  }

  private static double divide(long numerator, long denominator) {
    return denominator <= 0L ? 0.0d : numerator / (double) denominator;
  }
}
