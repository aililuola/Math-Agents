package io.github.aililuola.mathproofmesh.desktop;

import io.github.aililuola.mathproofmesh.config.StrictYamlConfigLoader;
import io.github.aililuola.mathproofmesh.config.SystemConfig;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.concurrency.AgentLeaseClass;
import io.github.aililuola.mathproofmesh.concurrency.ConcurrencyMetrics;
import io.github.aililuola.mathproofmesh.concurrency.FrozenResearchSnapshot;
import io.github.aililuola.mathproofmesh.concurrency.ResearchAuthorityAnchor;
import io.github.aililuola.mathproofmesh.concurrency.ResearchEpochLedger;
import io.github.aililuola.mathproofmesh.concurrency.ResearchMergePlan;
import io.github.aililuola.mathproofmesh.concurrency.ResearchResultLedger;
import io.github.aililuola.mathproofmesh.concurrency.ResearchTaskLedger;
import io.github.aililuola.mathproofmesh.concurrency.ResearchWorkConflictSet;
import io.github.aililuola.mathproofmesh.concurrency.ResearchWorkItem;
import io.github.aililuola.mathproofmesh.concurrency.ResearchWorkKind;
import io.github.aililuola.mathproofmesh.concurrency.ResearchWorkReadSet;
import io.github.aililuola.mathproofmesh.concurrency.ResearchWorkResultEnvelope;
import io.github.aililuola.mathproofmesh.concurrency.ResearchWorkResultStatus;
import io.github.aililuola.mathproofmesh.provider.AgentPool;
import io.github.aililuola.mathproofmesh.provider.ChatMessage;
import io.github.aililuola.mathproofmesh.provider.LLMResponse;
import io.github.aililuola.mathproofmesh.provider.MockResponder;
import io.github.aililuola.mathproofmesh.provider.ProviderClientRegistry;
import io.github.aililuola.mathproofmesh.provider.ProviderRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

final class DesktopResearchConcurrencyTestSupport {
  private DesktopResearchConcurrencyTestSupport() {}

  static Run run(ResearchWorkKind kind, int count, Delay delay) {
    Tracker tracker = new Tracker(delay);
    SystemConfig config = config();
    Map<String, MockResponder> responders =
        config.agents().stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    agent -> agent.id(), agent -> tracker::respond));
    try (ProviderClientRegistry providers =
            new ProviderClientRegistry(
                config,
                responders,
                ignored -> request -> {
                  throw new AssertionError("mock provider must not use HTTP");
                },
                false);
        AgentPool pool = new AgentPool(config, providers)) {
      ResearchEpochLedger epochs = new ResearchEpochLedger();
      ResearchTaskLedger tasks = new ResearchTaskLedger();
      ResearchResultLedger results = new ResearchResultLedger();
      FrozenResearchSnapshot snapshot = snapshot("epoch-" + kind.name().toLowerCase());
      List<ResearchWorkItem> workItems = items(snapshot, kind, count);
      DesktopResearchEpochExecutor executor =
          new DesktopResearchEpochExecutor(
              "run-concurrency",
              pool,
              config.concurrency().maxInFlightTasks(),
              (frozen, item, lease) -> {
                lease.call(
                    ProviderRequest.json(
                        List.of(new ChatMessage("user", item.workItemId())), 64, false));
                return new ResearchWorkResultEnvelope(
                    item.workItemId(),
                    frozen.epochId(),
                    frozen.snapshotHash(),
                    lease.agent().id(),
                    "request-" + item.workItemId(),
                    ResearchWorkResultStatus.SUCCEEDED,
                    Map.of("ordinal", item.stableOrdinal(), "kind", item.kind().name()),
                    List.of(),
                    List.of(),
                    List.of());
              },
              epochs,
              tasks,
              results);
      List<ResearchWorkResultEnvelope> settled = executor.execute(snapshot, workItems);
      return new Run(
          snapshot,
          workItems,
          settled,
          executor.latestMergePlan(),
          tracker.maximumActive.get(),
          tracker.startedOrder(),
          tracker.completedOrder(),
          pool.concurrencyMetrics(),
          CanonicalJson.stableHash(epochs.snapshot()),
          CanonicalJson.stableHash(tasks.snapshot()),
          CanonicalJson.stableHash(results.snapshot()),
          CanonicalJson.stableHash(pool.leaseSnapshot()),
          CanonicalJson.stableHash(pool.concurrencyTelemetrySnapshot()));
    }
  }

  static SystemConfig config() {
    return new StrictYamlConfigLoader()
        .read(
            """
            agents:
              - {id: agent-0, provider: mock, model: mock, roles: [explorer, route_referee], trust_prior: 1.0, max_concurrency: 1}
              - {id: agent-1, provider: mock, model: mock, roles: [explorer, route_referee], trust_prior: 0.9, max_concurrency: 1}
              - {id: agent-2, provider: mock, model: mock, roles: [explorer, route_referee], trust_prior: 0.8, max_concurrency: 1}
              - {id: agent-3, provider: mock, model: mock, roles: [explorer, route_referee], trust_prior: 0.7, max_concurrency: 1}
              - {id: agent-4, provider: mock, model: mock, roles: [explorer, route_referee], trust_prior: 0.6, max_concurrency: 1}
            runtime: {max_parallel_calls: 5, request_retries: 0}
            concurrency:
              research_slots: 4
              coordination_slots: 1
              max_in_flight_tasks: 5
              max_focused_parallel_roles: 4
              reserve_coordination_capacity: true
              lease_timeout_seconds: 5
            """);
  }

  static AgentPool idlePool() {
    SystemConfig config = config();
    Map<String, MockResponder> responders =
        config.agents().stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    agent -> agent.id(),
                    agent ->
                        ignored ->
                            new LLMResponse(
                                "{}", "mock", "mock", 0, 0, 1.0d, "idle", "stop", false, null)));
    return new AgentPool(
        config,
        new ProviderClientRegistry(
            config,
            responders,
            ignored -> request -> {
              throw new AssertionError("mock provider must not use HTTP");
            },
            false));
  }

  static FrozenResearchSnapshot snapshot(String epochId) {
    ResearchAuthorityAnchor authority =
        new ResearchAuthorityAnchor(
            "problem-hash",
            "root-goal-hash",
            "negative-hash",
            "attempt-hash",
            "claim-hash",
            "checkpoint-hash",
            "graph-hash",
            "canonical-hash",
            "convergence-hash",
            "pivot-hash",
            "portfolio-hash",
            "court-hash",
            "broker-hash",
            "computation-hash",
            "run-authority-hash");
    return new FrozenResearchSnapshot(epochId, authority, Map.of("problem", "artifact://problem"));
  }

  static List<ResearchWorkItem> items(
      FrozenResearchSnapshot snapshot, ResearchWorkKind kind, int count) {
    List<ResearchWorkItem> items = new ArrayList<>();
    for (int ordinal = 0; ordinal < count; ordinal++) {
      String route = "route-" + ordinal;
      String claim = "claim-" + ordinal;
      String workId =
          ResearchWorkItem.deterministicId(
              snapshot.epochId(), kind, route, claim, "obligation-" + ordinal, ordinal);
      items.add(
          new ResearchWorkItem(
              workId,
              snapshot.epochId(),
              snapshot.snapshotHash(),
              kind,
              route,
              claim,
              "obligation-" + ordinal,
              "target-" + ordinal,
              "explorer",
              AgentLeaseClass.RESEARCH,
              java.util.Set.of(),
              new ResearchWorkReadSet(java.util.Set.of("root-goal-hash"), java.util.Set.of()),
              ResearchWorkConflictSet.empty(),
              "artifact://input/" + ordinal,
              "result-schema",
              ordinal));
    }
    return List.copyOf(items);
  }

  @FunctionalInterface
  interface Delay {
    long millis(String workItemId);
  }

  record Run(
      FrozenResearchSnapshot snapshot,
      List<ResearchWorkItem> workItems,
      List<ResearchWorkResultEnvelope> results,
      ResearchMergePlan mergePlan,
      int maximumConcurrency,
      List<String> startedOrder,
      List<String> completedOrder,
      ConcurrencyMetrics metrics,
      String epochHash,
      String taskHash,
      String resultHash,
      String leaseHash,
      String telemetryHash) {}

  private static final class Tracker {
    private final Delay delay;
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger maximumActive = new AtomicInteger();
    private final AtomicInteger sequence = new AtomicInteger();
    private final Map<Integer, String> starts = new ConcurrentHashMap<>();
    private final Map<Integer, String> completions = new ConcurrentHashMap<>();

    private Tracker(Delay delay) {
      this.delay = delay;
    }

    private LLMResponse respond(ProviderRequest request) {
      String workItemId = request.messages().getFirst().content();
      starts.put(sequence.incrementAndGet(), workItemId);
      int now = active.incrementAndGet();
      maximumActive.accumulateAndGet(now, Math::max);
      try {
        Thread.sleep(delay.millis(workItemId));
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("mock provider interrupted", exception);
      } finally {
        active.decrementAndGet();
      }
      completions.put(sequence.incrementAndGet(), workItemId);
      return new LLMResponse(
          "{}", "mock", "mock", 1, 1, 1.0d, "provider-" + workItemId, "stop", false, null);
    }

    private List<String> startedOrder() {
      return starts.entrySet().stream()
          .sorted(Map.Entry.comparingByKey())
          .map(Map.Entry::getValue)
          .toList();
    }

    private List<String> completedOrder() {
      return completions.entrySet().stream()
          .sorted(Map.Entry.comparingByKey())
          .map(Map.Entry::getValue)
          .toList();
    }
  }
}
