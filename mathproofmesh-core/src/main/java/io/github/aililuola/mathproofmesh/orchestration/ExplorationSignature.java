package io.github.aililuola.mathproofmesh.orchestration;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/** Mathematical signature intentionally excludes checkpoint and obligation identities. */
public record ExplorationSignature(
    String domain, String mechanism, List<String> transformations, String digest) {
  public ExplorationSignature {
    domain = required(domain, "domain");
    mechanism = required(mechanism, "mechanism");
    transformations = transformations == null ? List.of() : List.copyOf(transformations);
    String expected = CanonicalJson.stableHash(List.of(domain, mechanism, transformations));
    digest = digest == null || digest.isBlank() ? expected : digest.strip();
    if (!MessageDigest.isEqual(
        digest.getBytes(StandardCharsets.US_ASCII),
        expected.getBytes(StandardCharsets.US_ASCII))) {
      throw new IllegalArgumentException("exploration signature digest mismatch");
    }
  }

  public ExplorationSignature(String domain, String mechanism, List<String> transformations) {
    this(domain, mechanism, transformations, null);
  }

  public List<String> transformations() {
    return List.copyOf(transformations);
  }

  private static String required(String value, String field) {
    String result = value == null ? "" : value.strip();
    if (result.isEmpty()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return result;
  }
}
