package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.config.SystemConfig;
import io.github.aililuola.mathproofmesh.concurrency.ResearchConcurrencyFailurePoint;
import io.github.aililuola.mathproofmesh.concurrency.ResearchEpochLedger;
import io.github.aililuola.mathproofmesh.concurrency.ResearchResultLedger;
import io.github.aililuola.mathproofmesh.concurrency.ResearchTaskLedger;
import io.github.aililuola.mathproofmesh.concurrency.ResearchWorkKind;
import io.github.aililuola.mathproofmesh.concurrency.ResearchWorkResultEnvelope;
import io.github.aililuola.mathproofmesh.concurrency.ResearchWorkResultStatus;
import io.github.aililuola.mathproofmesh.provider.AgentPool;
import io.github.aililuola.mathproofmesh.provider.ChatMessage;
import io.github.aililuola.mathproofmesh.provider.LLMResponse;
import io.github.aililuola.mathproofmesh.provider.MockResponder;
import io.github.aililuola.mathproofmesh.provider.ProviderClientRegistry;
import io.github.aililuola.mathproofmesh.provider.ProviderRequest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

final class DesktopConcurrentTaskHardCrashReplayBlackBoxTest {
  @Test
  void durableProviderResponsesAreNotCalledAgainAfterRestore() {
    SystemConfig config = DesktopResearchConcurrencyTestSupport.config();
    AtomicInteger providerCalls = new AtomicInteger();
    var snapshot = DesktopResearchConcurrencyTestSupport.snapshot("hard-crash-epoch");
    var items =
        DesktopResearchConcurrencyTestSupport.items(
            snapshot, ResearchWorkKind.ROUTE_REVIEW, 4);
    ResearchEpochLedger firstEpochs = new ResearchEpochLedger();
    ResearchTaskLedger firstTasks = new ResearchTaskLedger();
    ResearchResultLedger firstResults = new ResearchResultLedger();

    try (ProviderClientRegistry providers = providers(config, providerCalls);
        AgentPool pool = new AgentPool(config, providers)) {
      DesktopResearchEpochExecutor crashing =
          executor(
              pool,
              firstEpochs,
              firstTasks,
              firstResults,
              providerCalls,
              point -> {
                if (point == ResearchConcurrencyFailurePoint.AFTER_TASK_RESULT_DURABLE) {
                  throw new SimulatedProcessTermination();
                }
              });
      assertThatThrownBy(() -> crashing.execute(snapshot, items))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("worker failed");
      int callsBeforeRestore = providerCalls.get();
      assertThat(callsBeforeRestore).isEqualTo(4);

      ResearchEpochLedger restoredEpochs = new ResearchEpochLedger();
      ResearchTaskLedger restoredTasks = new ResearchTaskLedger();
      ResearchResultLedger restoredResults = new ResearchResultLedger();
      restoredEpochs.restore(firstEpochs.snapshot());
      restoredTasks.restore(firstTasks.snapshot());
      restoredResults.restore(firstResults.snapshot());

      DesktopResearchEpochExecutor restored =
          executor(
              pool,
              restoredEpochs,
              restoredTasks,
              restoredResults,
              providerCalls,
              ignored -> {});
      var results = restored.execute(snapshot, items);

      assertThat(results).hasSize(4);
      assertThat(providerCalls.get()).isEqualTo(callsBeforeRestore);
      assertThat(restored.latestMergePlan().decisions())
          .allMatch(decision -> decision.accepted());
      assertThat(pool.leaseSnapshot().leases())
          .allMatch(
              lease ->
                  lease.status()
                          != io.github.aililuola.mathproofmesh.concurrency.AgentLeaseStatus.ACQUIRED
                      && lease.status()
                          != io.github.aililuola.mathproofmesh.concurrency.AgentLeaseStatus.RUNNING);
    }
  }

  private static DesktopResearchEpochExecutor executor(
      AgentPool pool,
      ResearchEpochLedger epochs,
      ResearchTaskLedger tasks,
      ResearchResultLedger results,
      AtomicInteger providerCalls,
      DesktopResearchEpochExecutor.FailureInjector failureInjector) {
    return new DesktopResearchEpochExecutor(
        "run-hard-crash",
        pool,
        5,
        (frozen, item, lease) -> {
          lease.call(
              ProviderRequest.json(
                  List.of(new ChatMessage("user", item.workItemId())), 32, false));
          return new ResearchWorkResultEnvelope(
              item.workItemId(),
              frozen.epochId(),
              frozen.snapshotHash(),
              lease.agent().id(),
              "request-" + item.workItemId(),
              ResearchWorkResultStatus.SUCCEEDED,
              Map.of("provider_calls", providerCalls.get()),
              List.of(),
              List.of(),
              List.of());
        },
        epochs,
        tasks,
        results,
        failureInjector);
  }

  private static ProviderClientRegistry providers(
      SystemConfig config, AtomicInteger providerCalls) {
    Map<String, MockResponder> responders =
        config.agents().stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    agent -> agent.id(),
                    agent ->
                        ignored -> {
                          int call = providerCalls.incrementAndGet();
                          return new LLMResponse(
                              "{}",
                              "mock",
                              "mock",
                              1,
                              1,
                              1.0d,
                              "provider-" + call,
                              "stop",
                              false,
                              null);
                        }));
    return new ProviderClientRegistry(
        config,
        responders,
        ignored -> request -> {
          throw new AssertionError("mock provider must not use HTTP");
        },
        false);
  }

  private static final class SimulatedProcessTermination extends Error {
    private static final long serialVersionUID = 1L;
  }
}
