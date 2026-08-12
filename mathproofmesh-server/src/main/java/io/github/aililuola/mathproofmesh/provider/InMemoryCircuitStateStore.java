package io.github.aililuola.mathproofmesh.provider;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class InMemoryCircuitStateStore implements CircuitStateStore {
  private final Map<String, ProviderCircuitSnapshot> states = new LinkedHashMap<>();

  @Override
  public synchronized Optional<ProviderCircuitSnapshot> load(String providerScope) {
    return Optional.ofNullable(states.get(providerScope));
  }

  @Override
  public synchronized void save(ProviderCircuitSnapshot snapshot) {
    states.put(snapshot.providerScope(), snapshot);
  }

  @Override
  public synchronized void delete(String providerScope) {
    states.remove(providerScope);
  }
}
