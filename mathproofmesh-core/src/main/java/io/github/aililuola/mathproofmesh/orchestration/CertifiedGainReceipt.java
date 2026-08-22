package io.github.aililuola.mathproofmesh.orchestration;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/** Budget-side evidence receipt. It records public deltas and grants no mathematical authority. */
public record CertifiedGainReceipt(
    String epochId,
    String authorityHashBefore,
    String authorityHashAfter,
    int admittedClaimDelta,
    int admittedFactDelta,
    int refutedObligationDelta,
    int closedObligationDelta,
    double proofDebtBefore,
    double proofDebtAfter,
    int verifiedCheckpointDelta,
    int novelStrategyDelta,
    boolean meaningfulGain,
    String receiptHash) {

  public CertifiedGainReceipt {
    epochId = required(epochId, "epochId");
    authorityHashBefore = required(authorityHashBefore, "authorityHashBefore");
    authorityHashAfter = required(authorityHashAfter, "authorityHashAfter");
    if (admittedClaimDelta < 0
        || admittedFactDelta < 0
        || refutedObligationDelta < 0
        || closedObligationDelta < 0
        || verifiedCheckpointDelta < 0
        || novelStrategyDelta < 0
        || !Double.isFinite(proofDebtBefore)
        || !Double.isFinite(proofDebtAfter)
        || proofDebtBefore < 0.0d
        || proofDebtAfter < 0.0d) {
      throw new IllegalArgumentException("certified gain counters must be finite and nonnegative");
    }
    meaningfulGain =
        admittedClaimDelta > 0
            || admittedFactDelta > 0
            || refutedObligationDelta > 0
            || closedObligationDelta > 0
            || verifiedCheckpointDelta > 0
            || novelStrategyDelta > 0
            || proofDebtAfter < proofDebtBefore;
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("epoch_id", epochId);
    payload.put("authority_hash_before", authorityHashBefore);
    payload.put("authority_hash_after", authorityHashAfter);
    payload.put("admitted_claim_delta", admittedClaimDelta);
    payload.put("admitted_fact_delta", admittedFactDelta);
    payload.put("refuted_obligation_delta", refutedObligationDelta);
    payload.put("closed_obligation_delta", closedObligationDelta);
    payload.put("proof_debt_before", proofDebtBefore);
    payload.put("proof_debt_after", proofDebtAfter);
    payload.put("verified_checkpoint_delta", verifiedCheckpointDelta);
    payload.put("novel_strategy_delta", novelStrategyDelta);
    payload.put("meaningful_gain", meaningfulGain);
    String expected = CanonicalJson.stableHash(payload);
    receiptHash = receiptHash == null || receiptHash.isBlank() ? expected : receiptHash.strip();
    if (!sameHash(expected, receiptHash)) {
      throw new IllegalArgumentException("certified gain receipt hash mismatch");
    }
  }

  private static String required(String value, String field) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return normalized;
  }

  private static boolean sameHash(String left, String right) {
    return MessageDigest.isEqual(
        left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
  }
}
