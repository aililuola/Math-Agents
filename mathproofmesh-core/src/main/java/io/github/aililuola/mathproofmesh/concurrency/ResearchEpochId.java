package io.github.aililuola.mathproofmesh.concurrency;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.util.Map;

public final class ResearchEpochId {
  private ResearchEpochId() {}

  public static String deterministic(String runId, int ordinal, String authorityHash) {
    if (ordinal < 0) {
      throw new IllegalArgumentException("ordinal must be nonnegative");
    }
    String hash =
        CanonicalJson.stableHash(
            Map.of("run_id", runId, "ordinal", ordinal, "authority_hash", authorityHash));
    return "epoch-" + hash.substring(0, 24);
  }
}
