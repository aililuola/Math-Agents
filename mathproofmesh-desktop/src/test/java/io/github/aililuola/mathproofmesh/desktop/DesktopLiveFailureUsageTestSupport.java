package io.github.aililuola.mathproofmesh.desktop;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.aililuola.mathproofmesh.api.RunExecutionBackend;
import io.github.aililuola.mathproofmesh.api.SolveRequest;
import io.github.aililuola.mathproofmesh.config.SystemConfig;
import io.github.aililuola.mathproofmesh.provider.HttpTransport;
import io.github.aililuola.mathproofmesh.provider.InMemoryProviderCallRepository;
import io.github.aililuola.mathproofmesh.provider.LLMResponse;
import io.github.aililuola.mathproofmesh.provider.MockResponder;
import io.github.aililuola.mathproofmesh.provider.ProviderCallPlan;
import io.github.aililuola.mathproofmesh.provider.ProviderCallRecord;
import io.github.aililuola.mathproofmesh.provider.ProviderCallRepository;
import io.github.aililuola.mathproofmesh.provider.ProviderCallState;
import io.github.aililuola.mathproofmesh.provider.ProviderCallTransition;
import io.github.aililuola.mathproofmesh.provider.ProviderClientRegistry;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

final class DesktopLiveFailureUsageTestSupport {
  static final String PROFILE = "scripted";
  static final String PROBLEM = "Prove that 1 + 1 = 2.";

  private DesktopLiveFailureUsageTestSupport() {}

  static DesktopLiveRunExecutionBackend backend(
      Path dataRoot, FailingProviderCallRepository repository) {
    return backend(dataRoot, repository, "usage-request-");
  }

  static DesktopLiveRunExecutionBackend backend(
      Path dataRoot, FailingProviderCallRepository repository, String requestIdPrefix) {
    DesktopRuntimeLocator locator =
        new DesktopRuntimeLocator(DesktopLiveRunExecutionBackendTest.projectRoot(), null);
    SystemConfig config = config(locator);
    DesktopLiveRuntimeFactory.PreparedRuntime prepared =
        new DesktopLiveRuntimeFactory.PreparedRuntime(PROFILE, config, Map.of(), false);
    DesktopLiveRuntimeFactory runtimes = mock(DesktopLiveRuntimeFactory.class);
    when(runtimes.prepare(eq(PROFILE), any(DesktopSettings.class))).thenReturn(prepared);
    when(runtimes.openProviders(prepared))
        .thenAnswer(ignored -> providers(config, requestIdPrefix));
    DesktopPaths paths = DesktopTestSupport.paths(dataRoot);
    return new DesktopLiveRunExecutionBackend(
        paths,
        new SettingsStore(paths.settingsFile(), DesktopTestSupport.MAPPER),
        runtimes,
        locator,
        new DockerSandboxPreflight(),
        () -> repository);
  }

  static SystemConfig config() {
    return config(
        new DesktopRuntimeLocator(DesktopLiveRunExecutionBackendTest.projectRoot(), null));
  }

  private static SystemConfig config(DesktopRuntimeLocator locator) {
    return DesktopLiveRunExecutionBackendTest.mockConfig(
        locator.loadProfile("proof-control-active.yaml"));
  }

  static RunExecutionBackend.RunExecutionResult execute(
      DesktopLiveRunExecutionBackend backend, String runId, Path runDirectory) {
    return backend.execute(
        new SolveRequest(PROBLEM, runId, null, PROFILE),
        runId,
        "trace-" + runId,
        runDirectory,
        (type, stage, agentId, status, summary, reference) -> {});
  }

  private static ProviderClientRegistry providers(SystemConfig config, String requestIdPrefix) {
    AtomicLong requestIds = new AtomicLong();
    MockResponder responder =
        request -> {
          LLMResponse source =
              DesktopLiveRunExecutionBackendTest.response(
                  request, DesktopLiveRunExecutionBackendTest.Mode.COMPLETED);
          return new LLMResponse(
              source.text(),
              source.model(),
              source.provider(),
              source.inputTokens(),
              source.outputTokens(),
              source.latencyMs(),
              requestIdPrefix + requestIds.incrementAndGet(),
              source.finishReason(),
              source.streaming(),
              source.metadata());
        };
    Map<String, MockResponder> responders = new LinkedHashMap<>();
    config.agents().forEach(agent -> responders.put(agent.id(), responder));
    HttpTransport noNetwork = request -> {
      throw new AssertionError("failure-usage test attempted network access");
    };
    return new ProviderClientRegistry(
        config, responders, ignored -> noNetwork, false, ignored -> null);
  }

  static final class FailingProviderCallRepository implements ProviderCallRepository {
    enum FailureMode {
      BEFORE_NEXT_PLAN,
      AFTER_RESPONSE_ARTIFACT
    }

    private final InMemoryProviderCallRepository delegate =
        new InMemoryProviderCallRepository();
    private final int successfulCallsBeforeFailure;
    private final FailureMode mode;
    private final Runnable beforeFailure;
    private final Supplier<? extends Error> hardFailure;
    private final AtomicInteger plannedCalls = new AtomicInteger();
    private final AtomicInteger successfulCalls = new AtomicInteger();

    FailingProviderCallRepository(
        int successfulCallsBeforeFailure, FailureMode mode, Runnable beforeFailure) {
      this(successfulCallsBeforeFailure, mode, beforeFailure, null);
    }

    FailingProviderCallRepository(
        int successfulCallsBeforeFailure,
        FailureMode mode,
        Runnable beforeFailure,
        Supplier<? extends Error> hardFailure) {
      this.successfulCallsBeforeFailure = successfulCallsBeforeFailure;
      this.mode = mode;
      this.beforeFailure = beforeFailure;
      this.hardFailure = hardFailure;
    }

    int successfulCalls() {
      return successfulCalls.get();
    }

    @Override
    public ProviderCallRecord plan(ProviderCallPlan plan) {
      if (mode == FailureMode.BEFORE_NEXT_PLAN
          && plannedCalls.getAndIncrement() >= successfulCallsBeforeFailure) {
        fail();
      }
      return delegate.plan(plan);
    }

    @Override
    public ProviderCallRecord transition(ProviderCallTransition transition) {
      if (mode == FailureMode.AFTER_RESPONSE_ARTIFACT
          && transition.target() == ProviderCallState.SUCCEEDED
          && successfulCalls.get() >= successfulCallsBeforeFailure) {
        fail();
      }
      ProviderCallRecord result = delegate.transition(transition);
      if (transition.target() == ProviderCallState.SUCCEEDED
          || transition.target() == ProviderCallState.AMBIGUOUS) {
        successfulCalls.incrementAndGet();
      }
      return result;
    }

    @Override
    public boolean markApplied(String runId, String callId, String applicationKey) {
      return delegate.markApplied(runId, callId, applicationKey);
    }

    @Override
    public Optional<ProviderCallRecord> findByIdempotencyKey(
        String runId, String idempotencyKey) {
      return delegate.findByIdempotencyKey(runId, idempotencyKey);
    }

    @Override
    public List<ProviderCallRecord> findByRun(String runId) {
      return delegate.findByRun(runId);
    }

    private void fail() {
      beforeFailure.run();
      if (hardFailure != null) {
        throw hardFailure.get();
      }
      throw new IllegalStateException("injected live provider usage failure");
    }
  }
}
