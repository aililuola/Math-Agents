package io.github.aililuola.mathproofmesh.concurrency;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Objects;

public record FrozenResearchSnapshot(
    String epochId,
    ResearchAuthorityAnchor authority,
    Map<String, String> publicInputRefs,
    String snapshotHash) {
  public FrozenResearchSnapshot {
    epochId = text(epochId, "epochId");
    authority = Objects.requireNonNull(authority, "authority");
    publicInputRefs = publicInputRefs == null ? Map.of() : Map.copyOf(publicInputRefs);
    String computed =
        CanonicalJson.stableHash(
            Map.of(
                "epoch_id", epochId,
                "authority_hash", authority.stableHash(),
                "public_input_refs", publicInputRefs));
    snapshotHash = snapshotHash == null || snapshotHash.isBlank() ? computed : snapshotHash.strip();
    if (!hashEquals(computed, snapshotHash)) {
      throw new IllegalArgumentException("snapshotHash does not match frozen content");
    }
  }

  public FrozenResearchSnapshot(
      String epochId, ResearchAuthorityAnchor authority, Map<String, String> publicInputRefs) {
    this(epochId, authority, publicInputRefs, "");
  }

  @Override
  public Map<String, String> publicInputRefs() {
    return Map.copyOf(publicInputRefs);
  }

  private static String text(String value, String label) {
    Objects.requireNonNull(value, label);
    String normalized = value.strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    return normalized;
  }

  private static boolean hashEquals(String left, String right) {
    return MessageDigest.isEqual(
        left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
  }
}
