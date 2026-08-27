package io.github.aililuola.mathproofmesh.agent;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Ephemeral original-trace evidence used only to verify marker-free recovery quotes. */
public record ResearchCheckpointFallbackEvidence(String trace, String traceSha256) {
  public ResearchCheckpointFallbackEvidence {
    trace = trace == null ? "" : trace;
    traceSha256 = traceSha256 == null ? "" : traceSha256.strip();
    if (traceSha256.isEmpty()
        || !MessageDigest.isEqual(
            CanonicalJson.stableHash(trace).getBytes(StandardCharsets.US_ASCII),
            traceSha256.getBytes(StandardCharsets.US_ASCII))) {
      throw new IllegalArgumentException("fallback reasoning trace hash mismatch");
    }
  }
}
