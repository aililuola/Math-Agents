package io.github.aililuola.mathproofmesh.provider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Provider-scope breaker whose complete state is written through a store. */
public final class ProviderCircuitBreaker {
  private final boolean enabled;
  private final int distinctKeyThreshold;
  private final Duration window;
  private final Duration cooldown;
  private final Set<Integer> terminalStatuses;
  private final Set<Integer> sharedAuthStatuses;
  private final CircuitStateStore store;
  private final Clock clock;

  public ProviderCircuitBreaker(
      boolean enabled,
      int distinctKeyThreshold,
      Duration window,
      Duration cooldown,
      Set<Integer> terminalStatuses,
      Set<Integer> sharedAuthStatuses,
      CircuitStateStore store,
      Clock clock) {
    if (distinctKeyThreshold < 2) {
      throw new IllegalArgumentException("distinctKeyThreshold must be at least two");
    }
    this.enabled = enabled;
    this.distinctKeyThreshold = distinctKeyThreshold;
    this.window = requirePositive(window, "window");
    this.cooldown = requirePositive(cooldown, "cooldown");
    this.terminalStatuses = Set.copyOf(terminalStatuses);
    this.sharedAuthStatuses = Set.copyOf(sharedAuthStatuses);
    this.store = Objects.requireNonNull(store, "store");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public synchronized void assertAvailable(String scope) {
    if (!enabled) {
      return;
    }
    Instant now = clock.instant();
    ProviderCircuitSnapshot state =
        store.load(scope).orElseGet(() -> empty(scope));
    if (state.openUntil() != null && state.openUntil().isAfter(now)) {
      throw open(state, now, null);
    }
    ProviderCircuitSnapshot pruned = prune(state, now);
    if (state.openUntil() != null || pruned.failures().size() != state.failures().size()) {
      if (pruned.failures().isEmpty()) {
        store.delete(scope);
      } else {
        store.save(pruned);
      }
    }
  }

  public synchronized void recordFailure(
      String scope, String agentId, ProviderException failure) {
    if (!enabled) {
      return;
    }
    Objects.requireNonNull(failure, "failure");
    int status = failure.statusCode() == null ? -1 : failure.statusCode();
    boolean immediate = terminalStatuses.contains(status);
    boolean establishedStreamDisconnect =
        failure.kind() == ProviderErrorKind.NETWORK
            && (failure.partialReasoningCharacters() > 0
                || !failure.partialPublicContent().isEmpty());
    boolean sharedCandidate =
        immediate
            || sharedAuthStatuses.contains(status)
            || status >= 500
            // A disconnected stream that already delivered model output is a
            // recoverable call-local interruption, not evidence that the shared
            // provider endpoint is unavailable. AgentRuntime resumes/retries it.
            || (failure.kind() == ProviderErrorKind.NETWORK && !establishedStreamDisconnect)
            || failure.kind() == ProviderErrorKind.TIMEOUT;
    if (!sharedCandidate) {
      return;
    }
    Instant now = clock.instant();
    ProviderCircuitSnapshot current =
        prune(store.load(scope).orElseGet(() -> empty(scope)), now);
    List<ProviderCircuitSnapshot.Failure> failures =
        new ArrayList<>(current.failures());
    failures.add(
        new ProviderCircuitSnapshot.Failure(
            now, agentId, failure.kind().name()));
    long nextVersion = current.version() + 1L;
    ProviderCircuitSnapshot updated =
        new ProviderCircuitSnapshot(scope, failures, null, nextVersion);
    Set<String> agents = distinctAgents(failures);
    if (immediate || agents.size() >= distinctKeyThreshold) {
      updated =
          new ProviderCircuitSnapshot(
              scope, failures, now.plus(cooldown), nextVersion);
      store.save(updated);
      throw open(updated, now, failure);
    }
    store.save(updated);
  }

  public synchronized void recordSuccess(String scope) {
    if (!enabled) {
      return;
    }
    ProviderCircuitSnapshot current = store.load(scope).orElse(null);
    if (current == null || current.openUntil() == null) {
      store.delete(scope);
    }
  }

  public ProviderCircuitSnapshot snapshot(String scope) {
    return store.load(scope).orElseGet(() -> empty(scope));
  }

  private ProviderCircuitSnapshot prune(
      ProviderCircuitSnapshot snapshot, Instant now) {
    Instant cutoff = now.minus(window);
    List<ProviderCircuitSnapshot.Failure> retained =
        snapshot.failures().stream()
            .filter(failure -> !failure.occurredAt().isBefore(cutoff))
            .sorted(Comparator.comparing(ProviderCircuitSnapshot.Failure::occurredAt))
            .toList();
    Instant openUntil =
        snapshot.openUntil() != null && snapshot.openUntil().isAfter(now)
            ? snapshot.openUntil()
            : null;
    return new ProviderCircuitSnapshot(
        snapshot.providerScope(), retained, openUntil, snapshot.version());
  }

  private static ProviderCircuitSnapshot empty(String scope) {
    return new ProviderCircuitSnapshot(scope, List.of(), null, 0L);
  }

  private ProviderCircuitOpenError open(
      ProviderCircuitSnapshot state, Instant now, Throwable cause) {
    Duration remaining =
        state.openUntil() == null
            ? cooldown
            : Duration.between(now, state.openUntil());
    return new ProviderCircuitOpenError(
        state.providerScope(),
        List.copyOf(distinctAgents(state.failures())),
        remaining.isNegative() ? Duration.ZERO : remaining,
        cause);
  }

  private static Set<String> distinctAgents(
      List<ProviderCircuitSnapshot.Failure> failures) {
    Set<String> agents = new LinkedHashSet<>();
    failures.stream()
        .sorted(Comparator.comparing(ProviderCircuitSnapshot.Failure::occurredAt))
        .map(ProviderCircuitSnapshot.Failure::agentId)
        .forEach(agents::add);
    return agents;
  }

  private static Duration requirePositive(Duration value, String label) {
    Objects.requireNonNull(value, label);
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(label + " must be positive");
    }
    return value;
  }
}
