package io.github.aililuola.mathproofmesh.strategydiversity;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class StrategyMechanismRegistry {
  private final Object lock = new Object();
  private final Map<String, StrategyMechanismSignature> signatures = new LinkedHashMap<>();
  private final Map<String, StrategyMechanismProfile> profiles = new LinkedHashMap<>();
  private final Set<String> legacyActiveStrategyIds = new LinkedHashSet<>();
  private long version;

  public void register(
      String strategyId,
      StrategyMechanismSignature signature,
      StrategyMechanismProfile profile,
      boolean legacyActive) {
    strategyId = StrategySemanticNormalizer.require(strategyId, "strategyId");
    java.util.Objects.requireNonNull(signature, "signature");
    java.util.Objects.requireNonNull(profile, "profile");
    synchronized (lock) {
      StrategyMechanismSignature existing = signatures.get(strategyId);
      if (existing != null && !existing.equals(signature)) {
        throw new IllegalStateException("strategy mechanism identity cannot change: " + strategyId);
      }
      boolean changed = existing == null || !profile.equals(profiles.get(strategyId));
      signatures.put(strategyId, signature);
      profiles.put(strategyId, profile);
      if (legacyActive) {
        changed |= legacyActiveStrategyIds.add(strategyId);
      }
      if (changed) {
        version++;
      }
    }
  }

  public Optional<StrategyMechanismSignature> signature(String strategyId) {
    synchronized (lock) {
      return Optional.ofNullable(signatures.get(strategyId));
    }
  }

  public Optional<StrategyMechanismProfile> profile(String strategyId) {
    synchronized (lock) {
      return Optional.ofNullable(profiles.get(strategyId));
    }
  }

  public StrategyMechanismSnapshot snapshot() {
    synchronized (lock) {
      return snapshotUnsafe();
    }
  }

  public String registryHash() {
    synchronized (lock) {
      return CanonicalJson.stableHash(snapshotUnsafe());
    }
  }

  public static StrategyMechanismRegistry restore(StrategyMechanismSnapshot snapshot) {
    StrategyMechanismRegistry registry = new StrategyMechanismRegistry();
    StrategyMechanismSnapshot source =
        snapshot == null ? StrategyMechanismSnapshot.empty() : snapshot;
    synchronized (registry.lock) {
      registry.signatures.putAll(source.signatures());
      registry.profiles.putAll(source.profiles());
      registry.legacyActiveStrategyIds.addAll(source.legacyActiveStrategyIds());
      registry.version = source.version();
    }
    return registry;
  }

  private StrategyMechanismSnapshot snapshotUnsafe() {
    return new StrategyMechanismSnapshot(
        StrategyMechanismSnapshot.CURRENT_SCHEMA_VERSION,
        signatures,
        profiles,
        legacyActiveStrategyIds,
        version);
  }
}
