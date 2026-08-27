package io.github.aililuola.mathproofmesh.strategydiversity;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class StrategyPortfolioRegistry {
  private final Object lock = new Object();
  private final Map<String, StrategyPortfolioDecision> decisions = new LinkedHashMap<>();
  private final Map<String, StrategyPortfolioApplyReceipt> receipts = new LinkedHashMap<>();
  private long version;

  public StrategyPortfolioDecision record(StrategyPortfolioDecision decision) {
    java.util.Objects.requireNonNull(decision, "decision");
    synchronized (lock) {
      StrategyPortfolioDecision existing = decisions.get(decision.episodeId());
      if (existing != null
          && !StrategySemanticNormalizer.hashEquals(
              existing.decisionHash(), decision.decisionHash())) {
        throw new IllegalStateException("committed portfolio decision cannot change");
      }
      if (existing == null) {
        decisions.put(decision.episodeId(), decision);
        version++;
        return decision;
      }
      return existing;
    }
  }

  public Optional<StrategyPortfolioDecision> find(String episodeId) {
    synchronized (lock) {
      return Optional.ofNullable(decisions.get(episodeId));
    }
  }

  public StrategyPortfolioApplyReceipt recordReceipt(
      String episodeId, StrategyPortfolioApplyReceipt receipt) {
    episodeId = StrategySemanticNormalizer.require(episodeId, "episodeId");
    java.util.Objects.requireNonNull(receipt, "receipt");
    synchronized (lock) {
      if (!decisions.containsKey(episodeId)) {
        throw new IllegalStateException("portfolio decision must precede its apply receipt");
      }
      StrategyPortfolioApplyReceipt existing = receipts.get(episodeId);
      if (existing != null && !existing.equals(receipt)) {
        throw new IllegalStateException("portfolio apply receipt cannot change");
      }
      if (existing == null) {
        receipts.put(episodeId, receipt);
        version++;
        return receipt;
      }
      return existing;
    }
  }

  public Optional<StrategyPortfolioApplyReceipt> receipt(String episodeId) {
    synchronized (lock) {
      return Optional.ofNullable(receipts.get(episodeId));
    }
  }

  public StrategyPortfolioSnapshot snapshot() {
    synchronized (lock) {
      return snapshotUnsafe();
    }
  }

  public String registryHash() {
    synchronized (lock) {
      return CanonicalJson.stableHash(snapshotUnsafe());
    }
  }

  public static StrategyPortfolioRegistry restore(StrategyPortfolioSnapshot snapshot) {
    StrategyPortfolioRegistry registry = new StrategyPortfolioRegistry();
    StrategyPortfolioSnapshot source =
        snapshot == null ? StrategyPortfolioSnapshot.empty() : snapshot;
    synchronized (registry.lock) {
      registry.decisions.putAll(source.decisions());
      registry.receipts.putAll(source.receipts());
      registry.version = source.version();
    }
    return registry;
  }

  private StrategyPortfolioSnapshot snapshotUnsafe() {
    return new StrategyPortfolioSnapshot(
        StrategyPortfolioSnapshot.CURRENT_SCHEMA_VERSION, decisions, receipts, version);
  }
}
