package io.github.aililuola.mathproofmesh.concurrency;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ResearchWorkResultEnvelope(
    String workItemId,
    String epochId,
    String snapshotHash,
    String agentId,
    String providerRequestId,
    ResearchWorkResultStatus status,
    Map<String, Object> publicStructuredResult,
    List<String> researchCheckpointRefs,
    List<String> computationRefs,
    List<String> usageRefs,
    String resultHash) {
  public ResearchWorkResultEnvelope {
    workItemId = text(workItemId, "workItemId");
    epochId = text(epochId, "epochId");
    snapshotHash = text(snapshotHash, "snapshotHash");
    agentId = text(agentId, "agentId");
    providerRequestId = text(providerRequestId, "providerRequestId");
    status = Objects.requireNonNull(status, "status");
    publicStructuredResult =
        publicStructuredResult == null ? Map.of() : Map.copyOf(publicStructuredResult);
    researchCheckpointRefs = safe(researchCheckpointRefs);
    computationRefs = safe(computationRefs);
    usageRefs = safe(usageRefs);
    String computed =
        CanonicalJson.stableHash(
            List.of(
                workItemId,
                epochId,
                snapshotHash,
                agentId,
                providerRequestId,
                status.name(),
                CanonicalJson.stableHash(publicStructuredResult),
                CanonicalJson.stableHash(researchCheckpointRefs),
                CanonicalJson.stableHash(computationRefs),
                CanonicalJson.stableHash(usageRefs)));
    resultHash = resultHash == null || resultHash.isBlank() ? computed : resultHash.strip();
    if (!hashEquals(computed, resultHash)) {
      throw new IllegalArgumentException("resultHash does not match result content");
    }
  }

  public ResearchWorkResultEnvelope(
      String workItemId,
      String epochId,
      String snapshotHash,
      String agentId,
      String providerRequestId,
      ResearchWorkResultStatus status,
      Map<String, Object> publicStructuredResult,
      List<String> researchCheckpointRefs,
      List<String> computationRefs,
      List<String> usageRefs) {
    this(
        workItemId,
        epochId,
        snapshotHash,
        agentId,
        providerRequestId,
        status,
        publicStructuredResult,
        researchCheckpointRefs,
        computationRefs,
        usageRefs,
        "");
  }

  @Override
  public Map<String, Object> publicStructuredResult() {
    return Map.copyOf(publicStructuredResult);
  }

  @Override
  public List<String> researchCheckpointRefs() {
    return List.copyOf(researchCheckpointRefs);
  }

  @Override
  public List<String> computationRefs() {
    return List.copyOf(computationRefs);
  }

  @Override
  public List<String> usageRefs() {
    return List.copyOf(usageRefs);
  }

  private static List<String> safe(List<String> values) {
    return values == null ? List.of() : List.copyOf(values);
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
