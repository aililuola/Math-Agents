package io.github.aililuola.mathproofmesh.orchestration;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Atomic per-signature leases, tier gates, strikes, and bounded repair lineage. */
public final class DeepExplorationRegistry {
  private final Set<String> running = new java.util.LinkedHashSet<>();
  private final Map<String, Integer> strikes = new LinkedHashMap<>();
  private final Map<String, Integer> repairs = new LinkedHashMap<>();
  private final Map<String, ExplorationAttemptRecord> attempts = new LinkedHashMap<>();

  public synchronized ExplorationAdmission admit(
      ExplorationSignature signature,
      ExplorationModel requested,
      ExplorationEvidence evidence) {
    java.util.Objects.requireNonNull(signature, "signature");
    ExplorationModel tier =
        requested == null ? ExplorationModel.DEEP_96K : requested;
    String key = signature.digest();
    if (running.contains(key)) {
      return new ExplorationAdmission(false, "", tier, 0, "signature already has a running lease");
    }
    if (tier == ExplorationModel.DEEP_128K
        && (!evidence.verified96kProgress() || !evidence.metaApproved128k())) {
      return new ExplorationAdmission(
          false, "", tier, 0, "128k requires verified 96k progress and meta approval");
    }
    if (tier == ExplorationModel.BOUNDED_REPAIR
        && repairs.getOrDefault(key, 0) >= 1) {
      return new ExplorationAdmission(false, "", tier, 0, "bounded repair already consumed");
    }
    int calls = tier.recoveryCalls();
    if (evidence.schedulableCalls() < calls) {
      return new ExplorationAdmission(
          false, "", tier, 0, "recovery and finalization capacity is unavailable");
    }
    String lease =
        "exploration_"
            + CanonicalJson.stableHash(List.of(key, tier.name(), attempts.size()))
                .substring(0, 16);
    running.add(key);
    if (tier == ExplorationModel.BOUNDED_REPAIR) {
      repairs.merge(key, 1, Integer::sum);
    }
    return new ExplorationAdmission(true, lease, tier, calls, "atomic signature lease granted");
  }

  public synchronized ExplorationAttemptRecord complete(
      ExplorationAdmission admission,
      ExplorationSignature signature,
      ExplorationOutcome outcome) {
    if (!admission.accepted() || !running.remove(signature.digest())) {
      throw new IllegalStateException("exploration lease is not active");
    }
    if (!outcome.verifiedProgress()) {
      strikes.merge(signature.digest(), 1, Integer::sum);
    } else {
      strikes.put(signature.digest(), 0);
    }
    ExplorationAttemptRecord record =
        new ExplorationAttemptRecord(
            admission.leaseId(),
            signature,
            admission.tier(),
            outcome,
            strikes.getOrDefault(signature.digest(), 0),
            true);
    attempts.put(admission.leaseId(), record);
    return record;
  }

  public synchronized boolean running(ExplorationSignature signature) {
    return running.contains(signature.digest());
  }

  public synchronized int strikes(ExplorationSignature signature) {
    return strikes.getOrDefault(signature.digest(), 0);
  }

  public synchronized Map<String, ExplorationAttemptRecord> snapshot() {
    return Map.copyOf(attempts);
  }

  public static int firstChunkTimeoutSeconds() {
    return 60;
  }

  public static int streamStallTimeoutSeconds() {
    return 300;
  }

  public static boolean elapsedTimeCountsAsMathematicalProgress() {
    return false;
  }
}
