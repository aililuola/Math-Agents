package io.github.aililuola.mathproofmesh.desktop;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.concurrency.AgentLeaseRequest;
import io.github.aililuola.mathproofmesh.concurrency.ConcurrencyEventType;
import io.github.aililuola.mathproofmesh.concurrency.FrozenResearchSnapshot;
import io.github.aililuola.mathproofmesh.concurrency.ResearchEpochExecutor;
import io.github.aililuola.mathproofmesh.concurrency.ResearchEpochLedger;
import io.github.aililuola.mathproofmesh.concurrency.ResearchConcurrencyFailurePoint;
import io.github.aililuola.mathproofmesh.concurrency.ResearchEpochRecord;
import io.github.aililuola.mathproofmesh.concurrency.ResearchEpochStatus;
import io.github.aililuola.mathproofmesh.concurrency.ResearchMergePlan;
import io.github.aililuola.mathproofmesh.concurrency.ResearchMergePlanner;
import io.github.aililuola.mathproofmesh.concurrency.ResearchReadyQueue;
import io.github.aililuola.mathproofmesh.concurrency.ResearchResultLedger;
import io.github.aililuola.mathproofmesh.concurrency.ResearchTaskLedger;
import io.github.aililuola.mathproofmesh.concurrency.ResearchWorkItem;
import io.github.aililuola.mathproofmesh.concurrency.ResearchWorkResultEnvelope;
import io.github.aililuola.mathproofmesh.concurrency.ResearchWorkResultStatus;
import io.github.aililuola.mathproofmesh.concurrency.ResearchWorkStatus;
import io.github.aililuola.mathproofmesh.provider.AgentLease;
import io.github.aililuola.mathproofmesh.provider.AgentPool;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Bounded virtual-thread executor that reserves credentials before task submission. */
public final class DesktopResearchEpochExecutor implements ResearchEpochExecutor {
  private final String runId;
  private final AgentPool pool;
  private final int maximumInFlight;
  private final Worker worker;
  private final ManagedWorker managedWorker;
  private final ResearchEpochLedger epochs;
  private final ResearchTaskLedger tasks;
  private final ResearchResultLedger results;
  private final FailureInjector failureInjector;
  private final DurableBoundaryListener durableBoundaryListener;
  private final ResearchMergePlanner mergePlanner = new ResearchMergePlanner();
  private volatile ResearchMergePlan latestMergePlan;

  public DesktopResearchEpochExecutor(
      String runId,
      AgentPool pool,
      int maximumInFlight,
      Worker worker,
      ResearchEpochLedger epochs,
      ResearchTaskLedger tasks,
      ResearchResultLedger results) {
    this(
        runId,
        pool,
        maximumInFlight,
        worker,
        null,
        epochs,
        tasks,
        results,
        ignored -> {},
        ignored -> {});
  }

  /**
   * Creates an epoch executor for orchestration work that acquires one or more provider leases at
   * the exact call sites inside the worker. The ready queue still bounds task concurrency, while
   * the worker never holds an idle outer credential reservation.
   */
  public DesktopResearchEpochExecutor(
      String runId,
      AgentPool pool,
      int maximumInFlight,
      ManagedWorker worker,
      ResearchEpochLedger epochs,
      ResearchTaskLedger tasks,
      ResearchResultLedger results) {
    this(
        runId,
        pool,
        maximumInFlight,
        null,
        worker,
        epochs,
        tasks,
        results,
        ignored -> {},
        ignored -> {});
  }

  DesktopResearchEpochExecutor(
      String runId,
      AgentPool pool,
      int maximumInFlight,
      ManagedWorker worker,
      ResearchEpochLedger epochs,
      ResearchTaskLedger tasks,
      ResearchResultLedger results,
      DurableBoundaryListener durableBoundaryListener) {
    this(
        runId,
        pool,
        maximumInFlight,
        null,
        worker,
        epochs,
        tasks,
        results,
        ignored -> {},
        durableBoundaryListener);
  }

  DesktopResearchEpochExecutor(
      String runId,
      AgentPool pool,
      int maximumInFlight,
      Worker worker,
      ResearchEpochLedger epochs,
      ResearchTaskLedger tasks,
      ResearchResultLedger results,
      FailureInjector failureInjector) {
    this(
        runId,
        pool,
        maximumInFlight,
        worker,
        null,
        epochs,
        tasks,
        results,
        failureInjector,
        ignored -> {});
  }

  DesktopResearchEpochExecutor(
      String runId,
      AgentPool pool,
      int maximumInFlight,
      Worker worker,
      ResearchEpochLedger epochs,
      ResearchTaskLedger tasks,
      ResearchResultLedger results,
      FailureInjector failureInjector,
      DurableBoundaryListener durableBoundaryListener) {
    this(
        runId,
        pool,
        maximumInFlight,
        worker,
        null,
        epochs,
        tasks,
        results,
        failureInjector,
        durableBoundaryListener);
  }

  DesktopResearchEpochExecutor(
      String runId,
      AgentPool pool,
      int maximumInFlight,
      ManagedWorker worker,
      ResearchEpochLedger epochs,
      ResearchTaskLedger tasks,
      ResearchResultLedger results,
      FailureInjector failureInjector) {
    this(
        runId,
        pool,
        maximumInFlight,
        null,
        worker,
        epochs,
        tasks,
        results,
        failureInjector,
        ignored -> {});
  }

  private DesktopResearchEpochExecutor(
      String runId,
      AgentPool pool,
      int maximumInFlight,
      Worker worker,
      ManagedWorker managedWorker,
      ResearchEpochLedger epochs,
      ResearchTaskLedger tasks,
      ResearchResultLedger results,
      FailureInjector failureInjector,
      DurableBoundaryListener durableBoundaryListener) {
    this.runId = requireText(runId, "runId");
    this.pool = Objects.requireNonNull(pool, "pool");
    if (maximumInFlight < 1) {
      throw new IllegalArgumentException("maximumInFlight must be positive");
    }
    this.maximumInFlight = maximumInFlight;
    if ((worker == null) == (managedWorker == null)) {
      throw new IllegalArgumentException("exactly one research worker mode is required");
    }
    this.worker = worker;
    this.managedWorker = managedWorker;
    this.epochs = Objects.requireNonNull(epochs, "epochs");
    this.tasks = Objects.requireNonNull(tasks, "tasks");
    this.results = Objects.requireNonNull(results, "results");
    this.failureInjector = Objects.requireNonNull(failureInjector, "failureInjector");
    this.durableBoundaryListener =
        Objects.requireNonNull(durableBoundaryListener, "durableBoundaryListener");
  }

  @Override
  public List<ResearchWorkResultEnvelope> execute(
      FrozenResearchSnapshot snapshot, List<ResearchWorkItem> workItems) {
    Objects.requireNonNull(snapshot, "snapshot");
    List<ResearchWorkItem> ordered =
        workItems.stream()
            .sorted(
                Comparator.comparingInt(ResearchWorkItem::stableOrdinal)
                    .thenComparing(ResearchWorkItem::routeId)
                    .thenComparing(ResearchWorkItem::claimId)
                    .thenComparing(ResearchWorkItem::obligationId)
                    .thenComparing(ResearchWorkItem::workItemId))
            .toList();
    ResearchEpochRecord epoch =
        epochs.plan(snapshot, ordered.stream().map(ResearchWorkItem::workItemId).toList());
    failAt(ResearchConcurrencyFailurePoint.AFTER_EPOCH_PLANNED);
    ordered.forEach(tasks::plan);
    failAt(ResearchConcurrencyFailurePoint.AFTER_TASKS_RECORDED);
    ResearchReadyQueue ready = new ResearchReadyQueue();
    List<ResearchWorkResultEnvelope> settled = new ArrayList<>();
    for (ResearchWorkItem item : ordered) {
      if (tasks.require(item.workItemId()).status().settled()) {
        settled.add(restoredResult(snapshot, item));
      } else {
        ready.addAll(List.of(item));
      }
    }
    if (epoch.status() == ResearchEpochStatus.MERGE_PREPARED
        || epoch.status() == ResearchEpochStatus.COMMITTED) {
      latestMergePlan = mergePlanner.plan(snapshot, ordered, settled);
      if (!epoch.mergePlanHash().equals(latestMergePlan.mergePlanHash())) {
        throw new IllegalStateException("restored merge plan hash does not match durable results");
      }
      return orderedResults(settled);
    }
    if (epoch.status() == ResearchEpochStatus.PLANNED) {
      epochs.transition(snapshot.epochId(), ResearchEpochStatus.DISPATCHING, null, null);
    } else if (epoch.status() != ResearchEpochStatus.DISPATCHING
        && epoch.status() != ResearchEpochStatus.ALL_SETTLED) {
      throw new IllegalStateException("research epoch cannot resume from " + epoch.status());
    }
    ordered.stream().filter(item -> !tasks.require(item.workItemId()).status().settled()).forEach(
        item ->
            pool.recordConcurrencyEvent(
                ConcurrencyEventType.WORK_QUEUED,
                snapshot.epochId(),
                item.workItemId(),
                "",
                ready.size()));
    List<ResearchWorkItem> inFlightItems = new ArrayList<>();
    boolean firstResultObserved = !settled.isEmpty();
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CompletionService<Completed> completions = new ExecutorCompletionService<>(executor);
      int inFlight = 0;
      while (!ready.isEmpty() || inFlight > 0) {
        boolean submitted = false;
        while (inFlight < maximumInFlight) {
          Optional<ResearchWorkItem> next = ready.pollCompatible(inFlightItems);
          if (next.isEmpty()) {
            break;
          }
          ResearchWorkItem item = next.orElseThrow();
          if (managedWorker != null) {
            tasks.transition(
                item.workItemId(), ResearchWorkStatus.RUNNING, "managed-worker", "", "", "");
            inFlightItems.add(item);
            completions.submit(() -> executeManaged(snapshot, item, ready));
            inFlight++;
            submitted = true;
            continue;
          }
          Optional<AgentLease> acquired = pool.tryAcquireLease(leaseRequest(item));
          if (acquired.isEmpty()) {
            ready.addAll(List.of(item));
            break;
          }
          AgentLease lease = acquired.orElseThrow();
          try {
            tasks.transition(
                item.workItemId(), ResearchWorkStatus.LEASED, lease.agent().id(), "", "", "");
            failAt(ResearchConcurrencyFailurePoint.AFTER_AGENT_LEASE_ACQUIRED);
          } catch (RuntimeException | Error failure) {
            lease.close();
            throw failure;
          }
          inFlightItems.add(item);
          completions.submit(() -> executeOne(snapshot, item, lease, ready));
          inFlight++;
          submitted = true;
        }
        if (inFlight == 0) {
          if (!ready.isEmpty() && !submitted) {
            throw new IllegalStateException("ready research work has no eligible credential lease");
          }
          continue;
        }
        Completed completed = take(completions);
        inFlight--;
        inFlightItems.removeIf(item -> item.workItemId().equals(completed.item().workItemId()));
        settled.add(completed.result());
        if (!firstResultObserved) {
          firstResultObserved = true;
          durableBoundaryListener.afterDurableBoundary(
              DesktopDurableBoundary.FIRST_RESULT_DURABLE);
        }
      }
    }
    if (!tasks.allSettled(snapshot.epochId())) {
      throw new IllegalStateException("research epoch reached barrier before all tasks settled");
    }
    if (epochs.require(snapshot.epochId()).status() != ResearchEpochStatus.ALL_SETTLED) {
      failAt(ResearchConcurrencyFailurePoint.AFTER_ALL_SETTLED);
      pool.recordConcurrencyEvent(
          ConcurrencyEventType.BARRIER_ENTERED, snapshot.epochId(), "", "", 0);
      epochs.transition(
          snapshot.epochId(),
          ResearchEpochStatus.ALL_SETTLED,
          settled.stream().map(ResearchWorkResultEnvelope::resultHash).toList(),
          null);
      pool.recordConcurrencyEvent(
          ConcurrencyEventType.BARRIER_RELEASED, snapshot.epochId(), "", "", 0);
      durableBoundaryListener.afterDurableBoundary(DesktopDurableBoundary.ALL_SETTLED);
    }
    pool.recordConcurrencyEvent(
        ConcurrencyEventType.MERGE_STARTED, snapshot.epochId(), "", "", 0);
    latestMergePlan = mergePlanner.plan(snapshot, ordered, settled);
    ResearchEpochStatus epochStatus = epochs.require(snapshot.epochId()).status();
    if (epochStatus != ResearchEpochStatus.MERGE_PREPARED) {
      failAt(ResearchConcurrencyFailurePoint.AFTER_MERGE_PLAN_DURABLE);
      epochs.transition(
          snapshot.epochId(),
          ResearchEpochStatus.MERGE_PREPARED,
          null,
          latestMergePlan.mergePlanHash());
      durableBoundaryListener.afterDurableBoundary(DesktopDurableBoundary.MERGE_PREPARED);
    } else if (!latestMergePlan
        .mergePlanHash()
        .equals(epochs.require(snapshot.epochId()).mergePlanHash())) {
      throw new IllegalStateException("restored research merge plan hash changed");
    }
    pool.recordConcurrencyEvent(
        ConcurrencyEventType.MERGE_COMPLETED, snapshot.epochId(), "", "", 0);
    return orderedResults(settled);
  }

  private List<ResearchWorkResultEnvelope> orderedResults(
      List<ResearchWorkResultEnvelope> settled) {
    return settled.stream()
        .sorted(
            Comparator.<ResearchWorkResultEnvelope>comparingInt(
                    result -> tasks.require(result.workItemId()).item().stableOrdinal())
                .thenComparing(ResearchWorkResultEnvelope::workItemId))
        .toList();
  }

  public ResearchMergePlan latestMergePlan() {
    ResearchMergePlan plan = latestMergePlan;
    if (plan == null) {
      throw new IllegalStateException("no research epoch has reached merge preparation");
    }
    return plan;
  }

  private Completed executeOne(
      FrozenResearchSnapshot snapshot,
      ResearchWorkItem item,
      AgentLease lease,
      ResearchReadyQueue ready) {
    try (lease) {
      tasks.transition(
          item.workItemId(),
          ResearchWorkStatus.RUNNING,
          lease.agent().id(),
          "provider-intent-" + item.workItemId(),
          "",
          "");
      ResearchWorkResultEnvelope result;
      try {
        result = worker.execute(snapshot, item, lease);
        validateResultBinding(snapshot, item, lease, result);
        failAt(ResearchConcurrencyFailurePoint.AFTER_PROVIDER_RESPONSE_DURABLE);
      } catch (RuntimeException failure) {
        result = failedResult(snapshot, item, lease, failure);
      }
      var artifact = results.store(result);
      failAt(ResearchConcurrencyFailurePoint.AFTER_TASK_RESULT_DURABLE);
      pool.recordConcurrencyEvent(
          ConcurrencyEventType.RESULT_DURABLE,
          snapshot.epochId(),
          item.workItemId(),
          lease.agent().id(),
          ready.size());
      ResearchWorkStatus terminal =
          result.status() == ResearchWorkResultStatus.SUCCEEDED
              ? ResearchWorkStatus.RESULT_DURABLE
              : result.status() == ResearchWorkResultStatus.QUARANTINED
                  ? ResearchWorkStatus.QUARANTINED_UNCERTAIN_CALL
                  : ResearchWorkStatus.FAILED_DURABLE;
      tasks.transition(
          item.workItemId(),
          terminal,
          lease.agent().id(),
          result.providerRequestId(),
          artifact.artifactRef(),
          result.resultHash());
      return new Completed(item, result);
    }
  }

  private Completed executeManaged(
      FrozenResearchSnapshot snapshot, ResearchWorkItem item, ResearchReadyQueue ready) {
    ResearchWorkResultEnvelope result;
    try {
      result = managedWorker.execute(snapshot, item);
      validateManagedResultBinding(snapshot, item, result);
      failAt(ResearchConcurrencyFailurePoint.AFTER_PROVIDER_RESPONSE_DURABLE);
    } catch (RuntimeException failure) {
      result = failedManagedResult(snapshot, item, failure);
    }
    var artifact = results.store(result);
    failAt(ResearchConcurrencyFailurePoint.AFTER_TASK_RESULT_DURABLE);
    pool.recordConcurrencyEvent(
        ConcurrencyEventType.RESULT_DURABLE,
        snapshot.epochId(),
        item.workItemId(),
        result.agentId(),
        ready.size());
    ResearchWorkStatus terminal =
        result.status() == ResearchWorkResultStatus.SUCCEEDED
            ? ResearchWorkStatus.RESULT_DURABLE
            : result.status() == ResearchWorkResultStatus.QUARANTINED
                ? ResearchWorkStatus.QUARANTINED_UNCERTAIN_CALL
                : ResearchWorkStatus.FAILED_DURABLE;
    tasks.transition(
        item.workItemId(),
        terminal,
        result.agentId(),
        result.providerRequestId(),
        artifact.artifactRef(),
        result.resultHash());
    return new Completed(item, result);
  }

  private ResearchWorkResultEnvelope restoredResult(
      FrozenResearchSnapshot snapshot, ResearchWorkItem item) {
    try {
      var artifact = results.require(item.workItemId());
      ResearchWorkResultEnvelope durable = artifact.envelope();
      if (durable.status() == ResearchWorkResultStatus.SUCCEEDED
          && tasks.require(item.workItemId()).status()
              == ResearchWorkStatus.QUARANTINED_UNCERTAIN_CALL) {
        tasks.transition(
            item.workItemId(),
            ResearchWorkStatus.RESULT_DURABLE,
            durable.agentId(),
            durable.providerRequestId(),
            artifact.artifactRef(),
            durable.resultHash());
      }
      return durable;
    } catch (IllegalArgumentException missing) {
      var record = tasks.require(item.workItemId());
      ResearchWorkResultEnvelope quarantined =
          new ResearchWorkResultEnvelope(
              item.workItemId(),
              snapshot.epochId(),
              snapshot.snapshotHash(),
              record.agentId().isEmpty() ? "unknown-restored-agent" : record.agentId(),
              record.providerRequestId().isEmpty()
                  ? "uncertain-" + item.workItemId()
                  : record.providerRequestId(),
              ResearchWorkResultStatus.QUARANTINED,
              Map.of("failure_code", "UNCERTAIN_PROVIDER_CALL_AFTER_RESTORE"),
              List.of(),
              List.of(),
              List.of());
      results.store(quarantined);
      return quarantined;
    }
  }

  private void failAt(ResearchConcurrencyFailurePoint point) {
    failureInjector.fail(point);
  }

  private AgentLeaseRequest leaseRequest(ResearchWorkItem item) {
    return new AgentLeaseRequest(
        runId,
        item.epochId(),
        item.workItemId(),
        item.leaseClass(),
        item.requiredRole(),
        item.excludedAgentIds(),
        List.of(),
        "",
        "",
        1);
  }

  private static String requireText(String value, String name) {
    String normalized = Objects.requireNonNull(value, name).strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return normalized;
  }

  private static void validateResultBinding(
      FrozenResearchSnapshot snapshot,
      ResearchWorkItem item,
      AgentLease lease,
      ResearchWorkResultEnvelope result) {
    if (!result.workItemId().equals(item.workItemId())
        || !result.epochId().equals(snapshot.epochId())
        || !result.snapshotHash().equals(snapshot.snapshotHash())
        || !result.agentId().equals(lease.agent().id())) {
      throw new IllegalArgumentException("worker result is not bound to its frozen task and lease");
    }
  }

  private static void validateManagedResultBinding(
      FrozenResearchSnapshot snapshot,
      ResearchWorkItem item,
      ResearchWorkResultEnvelope result) {
    if (!result.workItemId().equals(item.workItemId())
        || !result.epochId().equals(snapshot.epochId())
        || !result.snapshotHash().equals(snapshot.snapshotHash())) {
      throw new IllegalArgumentException("managed worker result is not bound to its frozen task");
    }
  }

  private static ResearchWorkResultEnvelope failedResult(
      FrozenResearchSnapshot snapshot,
      ResearchWorkItem item,
      AgentLease lease,
      RuntimeException failure) {
    return new ResearchWorkResultEnvelope(
        item.workItemId(),
        snapshot.epochId(),
        snapshot.snapshotHash(),
        lease.agent().id(),
        "failed-" + item.workItemId(),
        ResearchWorkResultStatus.FAILED,
        Map.of("failure_code", failure.getClass().getSimpleName()),
        List.of(),
        List.of(),
        List.of());
  }

  private static ResearchWorkResultEnvelope failedManagedResult(
      FrozenResearchSnapshot snapshot,
      ResearchWorkItem item,
      RuntimeException failure) {
    return new ResearchWorkResultEnvelope(
        item.workItemId(),
        snapshot.epochId(),
        snapshot.snapshotHash(),
        "managed-worker",
        "failed-" + item.workItemId(),
        ResearchWorkResultStatus.FAILED,
        Map.of(
            "failure_code", failure.getClass().getSimpleName(),
            "failure_detail", safeFailureDetail(failure)),
        List.of(),
        List.of(),
        List.of());
  }

  private static String safeFailureDetail(RuntimeException failure) {
    String message = failure.getMessage();
    if (message == null || message.isBlank()) {
      return failure.getClass().getSimpleName();
    }
    String normalized = message.strip();
    return normalized.length() <= 256 ? normalized : normalized.substring(0, 256);
  }

  @SuppressFBWarnings(
      value = "THROWS_METHOD_THROWS_RUNTIMEEXCEPTION",
      justification =
          "The completion boundary must propagate the worker's original runtime failure so the "
              + "epoch can restore its atomic snapshot and preserve failure classification.")
  private Completed take(CompletionService<Completed> completions) {
    try {
      return completions.take().get();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("research epoch interrupted", exception);
    } catch (ExecutionException exception) {
      Throwable cause = exception.getCause();
      if (cause instanceof RuntimeException runtime) {
        throw runtime;
      }
      if (cause instanceof Error error && managedWorker != null) {
        throw error;
      }
      throw new IllegalStateException("research epoch worker failed", cause);
    }
  }

  @FunctionalInterface
  public interface Worker {
    ResearchWorkResultEnvelope execute(
        FrozenResearchSnapshot snapshot, ResearchWorkItem item, AgentLease lease);
  }

  @FunctionalInterface
  public interface ManagedWorker {
    ResearchWorkResultEnvelope execute(FrozenResearchSnapshot snapshot, ResearchWorkItem item);
  }

  @FunctionalInterface
  interface FailureInjector {
    void fail(ResearchConcurrencyFailurePoint point);
  }

  @FunctionalInterface
  interface DurableBoundaryListener {
    void afterDurableBoundary(DesktopDurableBoundary boundary);
  }

  private record Completed(ResearchWorkItem item, ResearchWorkResultEnvelope result) {}
}
