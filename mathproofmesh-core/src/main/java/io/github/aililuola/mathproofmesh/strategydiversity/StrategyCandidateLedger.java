package io.github.aililuola.mathproofmesh.strategydiversity;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class StrategyCandidateLedger {
  private final Object lock = new Object();
  private final Map<String, StrategyCandidateRecord> records = new LinkedHashMap<>();
  private long version;

  public StrategyCandidateRecord capture(
      String episodeId, String strategyId, int captureOrder, boolean replenishment) {
    synchronized (lock) {
      StrategyCandidateRecord existing = records.get(strategyId);
      if (existing != null) {
        return existing;
      }
      StrategyCandidateRecord created =
          new StrategyCandidateRecord(
              strategyId,
              episodeId,
              replenishment
                  ? StrategyCandidateStatus.REPLENISHMENT_CANDIDATE
                  : StrategyCandidateStatus.CAPTURED,
              "",
              "",
              null,
              "",
              captureOrder,
              1L);
      records.put(strategyId, created);
      version++;
      return created;
    }
  }

  public StrategyCandidateRecord transition(
      String strategyId,
      StrategyCandidateStatus status,
      String mechanismSignatureHash,
      String preflightReportHash,
      Double calibratedScore,
      String detail) {
    synchronized (lock) {
      StrategyCandidateRecord existing = records.get(strategyId);
      if (existing == null) {
        throw new IllegalArgumentException("unknown strategy candidate: " + strategyId);
      }
      if (terminal(existing.status()) && existing.status() != status) {
        throw new IllegalStateException("terminal candidate status cannot change: " + strategyId);
      }
      StrategyCandidateRecord updated =
          new StrategyCandidateRecord(
              existing.strategyId(),
              existing.episodeId(),
              status,
              choose(mechanismSignatureHash, existing.mechanismSignatureHash()),
              choose(preflightReportHash, existing.preflightReportHash()),
              calibratedScore == null ? existing.calibratedScore() : calibratedScore,
              detail,
              existing.captureOrder(),
              existing.version() + 1L);
      records.put(strategyId, updated);
      version++;
      return updated;
    }
  }

  public Optional<StrategyCandidateRecord> find(String strategyId) {
    synchronized (lock) {
      return Optional.ofNullable(records.get(strategyId));
    }
  }

  public StrategyCandidateSnapshot snapshot() {
    synchronized (lock) {
      return new StrategyCandidateSnapshot(
          StrategyCandidateSnapshot.CURRENT_SCHEMA_VERSION, records, version);
    }
  }

  public String ledgerHash() {
    synchronized (lock) {
      return CanonicalJson.stableHash(
          new StrategyCandidateSnapshot(
              StrategyCandidateSnapshot.CURRENT_SCHEMA_VERSION, records, version));
    }
  }

  public static StrategyCandidateLedger restore(StrategyCandidateSnapshot snapshot) {
    StrategyCandidateLedger ledger = new StrategyCandidateLedger();
    StrategyCandidateSnapshot source =
        snapshot == null ? StrategyCandidateSnapshot.empty() : snapshot;
    synchronized (ledger.lock) {
      ledger.records.putAll(source.records());
      ledger.version = source.version();
    }
    return ledger;
  }

  private static boolean terminal(StrategyCandidateStatus status) {
    return switch (status) {
      case SELECTED,
          NOT_SELECTED,
          REJECTED_INVALID,
          REJECTED_NEGATIVE,
          REJECTED_REFUTED_REQUIRED_CLAIM,
          QUARANTINED_PREFLIGHT_ERROR,
          QUARANTINED_COMMON_MODE,
          SHADOW_DUPLICATE,
          LEGACY_ACTIVE -> true;
      default -> false;
    };
  }

  private static String choose(String proposed, String current) {
    return proposed == null || proposed.isBlank() ? current : proposed.strip();
  }
}
