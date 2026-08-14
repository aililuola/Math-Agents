package io.github.aililuola.mathproofmesh.strategydiversity;

import java.util.Map;
import java.util.Set;

public record StrategyMechanismSnapshot(
    int schemaVersion,
    Map<String, StrategyMechanismSignature> signatures,
    Map<String, StrategyMechanismProfile> profiles,
    Set<String> legacyActiveStrategyIds,
    long version) {
  public static final int CURRENT_SCHEMA_VERSION = 1;

  public StrategyMechanismSnapshot {
    signatures = signatures == null ? Map.of() : Map.copyOf(signatures);
    profiles = profiles == null ? Map.of() : Map.copyOf(profiles);
    legacyActiveStrategyIds =
        legacyActiveStrategyIds == null ? Set.of() : Set.copyOf(legacyActiveStrategyIds);
    if (version < 0L) {
      throw new IllegalArgumentException("version must be nonnegative");
    }
  }

  public static StrategyMechanismSnapshot empty() {
    return new StrategyMechanismSnapshot(CURRENT_SCHEMA_VERSION, Map.of(), Map.of(), Set.of(), 0L);
  }

  @Override
  public Map<String, StrategyMechanismSignature> signatures() {
    return Map.copyOf(signatures);
  }

  @Override
  public Map<String, StrategyMechanismProfile> profiles() {
    return Map.copyOf(profiles);
  }

  @Override
  public Set<String> legacyActiveStrategyIds() {
    return Set.copyOf(legacyActiveStrategyIds);
  }
}
