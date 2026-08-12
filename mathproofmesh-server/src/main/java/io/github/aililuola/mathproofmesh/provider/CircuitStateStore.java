package io.github.aililuola.mathproofmesh.provider;

import java.util.Optional;

public interface CircuitStateStore {
  Optional<ProviderCircuitSnapshot> load(String providerScope);

  void save(ProviderCircuitSnapshot snapshot);

  void delete(String providerScope);
}
