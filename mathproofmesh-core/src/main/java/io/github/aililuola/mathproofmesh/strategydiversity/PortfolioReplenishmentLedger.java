package io.github.aililuola.mathproofmesh.strategydiversity;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class PortfolioReplenishmentLedger {
  private final Object lock = new Object();
  private final Map<String, PortfolioReplenishmentSnapshot.ReplenishmentRecord> episodes =
      new LinkedHashMap<>();
  private long version;

  public boolean mayRequest(String episodeId) {
    synchronized (lock) {
      return !episodes.containsKey(episodeId);
    }
  }

  public void begin(String episodeId, String requestHash) {
    episodeId = StrategySemanticNormalizer.require(episodeId, "episodeId");
    synchronized (lock) {
      if (episodes.containsKey(episodeId)) {
        throw new IllegalStateException("replenishment already requested for " + episodeId);
      }
      episodes.put(
          episodeId,
          new PortfolioReplenishmentSnapshot.ReplenishmentRecord(
              episodeId, true, false, 1, List.of(), requestHash));
      version++;
    }
  }

  public void complete(String episodeId, List<String> candidateIds) {
    synchronized (lock) {
      PortfolioReplenishmentSnapshot.ReplenishmentRecord existing = episodes.get(episodeId);
      if (existing == null || existing.completed()) {
        throw new IllegalStateException("replenishment is not pending for " + episodeId);
      }
      episodes.put(
          episodeId,
          new PortfolioReplenishmentSnapshot.ReplenishmentRecord(
              existing.episodeId(),
              true,
              true,
              existing.providerCalls(),
              candidateIds,
              existing.requestHash()));
      version++;
    }
  }

  public Optional<PortfolioReplenishmentSnapshot.ReplenishmentRecord> find(String episodeId) {
    synchronized (lock) {
      return Optional.ofNullable(episodes.get(episodeId));
    }
  }

  public PortfolioReplenishmentSnapshot snapshot() {
    synchronized (lock) {
      return new PortfolioReplenishmentSnapshot(
          PortfolioReplenishmentSnapshot.CURRENT_SCHEMA_VERSION, episodes, version);
    }
  }

  public String ledgerHash() {
    synchronized (lock) {
      return CanonicalJson.stableHash(
          new PortfolioReplenishmentSnapshot(
              PortfolioReplenishmentSnapshot.CURRENT_SCHEMA_VERSION, episodes, version));
    }
  }

  public static PortfolioReplenishmentLedger restore(PortfolioReplenishmentSnapshot snapshot) {
    PortfolioReplenishmentLedger ledger = new PortfolioReplenishmentLedger();
    PortfolioReplenishmentSnapshot source =
        snapshot == null ? PortfolioReplenishmentSnapshot.empty() : snapshot;
    synchronized (ledger.lock) {
      ledger.episodes.putAll(source.episodes());
      ledger.version = source.version();
    }
    return ledger;
  }
}
