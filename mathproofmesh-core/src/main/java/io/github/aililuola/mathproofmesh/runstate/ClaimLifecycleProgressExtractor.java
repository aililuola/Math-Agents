package io.github.aililuola.mathproofmesh.runstate;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Extracts conservative claim authority from modern and legacy lifecycle snapshots. */
public final class ClaimLifecycleProgressExtractor {
  private static final Set<String> VERIFIED_STATES =
      Set.of(
          "VERIFIED",
          "verified",
          "LOCALLY_VERIFIED",
          "INDEPENDENTLY_VERIFIED",
          "REFEREE_ACCEPTED",
          "FACT_CANDIDATE",
          "EXTERNALLY_ADMITTED_FACT");
  private static final String VERIFIED_REFUTATION_PREFIX =
      "verified exact statement refutation ";

  private ClaimLifecycleProgressExtractor() {}

  public record Progress(List<String> verifiedClaimIds, List<String> refutedClaimIds) {
    public Progress {
      verifiedClaimIds = normalized(verifiedClaimIds);
      refutedClaimIds = normalized(refutedClaimIds);
    }

    @Override
    public List<String> verifiedClaimIds() {
      return List.copyOf(verifiedClaimIds);
    }

    @Override
    public List<String> refutedClaimIds() {
      return List.copyOf(refutedClaimIds);
    }
  }

  public static Progress extract(JsonNode lifecycle) {
    if (lifecycle == null || lifecycle.isMissingNode() || lifecycle.isNull()) {
      return new Progress(List.of(), List.of());
    }
    Set<String> verified = new LinkedHashSet<>();
    Set<String> refuted = new LinkedHashSet<>();
    collect(lifecycle.path("entries"), true, verified, refuted);
    collect(lifecycle.path("records"), false, verified, refuted);
    return new Progress(List.copyOf(verified), List.copyOf(refuted));
  }

  private static void collect(
      JsonNode records, boolean modern, Set<String> verified, Set<String> refuted) {
    if (records.isObject()) {
      records.properties()
          .forEach(
              entry -> classify(entry.getValue(), entry.getKey(), modern, verified, refuted));
    } else if (records.isArray()) {
      records.forEach(node -> classify(node, "", modern, verified, refuted));
    }
  }

  private static void classify(
      JsonNode node,
      String fallbackId,
      boolean modern,
      Set<String> verified,
      Set<String> refuted) {
    String claimId = node.path("claimId").asText(fallbackId).strip();
    if (claimId.isEmpty()) {
      return;
    }
    String state =
        node.path(modern ? "state" : "status")
            .asText(node.path("state").asText(node.path("status").asText("")))
            .strip();
    if (VERIFIED_STATES.contains(state)) {
      verified.add(claimId);
    }
    if (Set.of("REFUTED", "refuted").contains(state)
        || (modern && trustedModernRefutation(node, state))) {
      refuted.add(claimId);
    }
  }

  private static boolean trustedModernRefutation(JsonNode node, String state) {
    if (!"REJECTED".equals(state)) {
      return false;
    }
    String reason = node.path("invalidationReason").asText("").strip();
    if (!reason.startsWith(VERIFIED_REFUTATION_PREFIX)
        || !node.path("invalidatingEvidenceIds").isArray()
        || node.path("invalidatingEvidenceIds").isEmpty()) {
      return false;
    }
    JsonNode history = node.path("history");
    if (!history.isArray()) {
      return false;
    }
    for (JsonNode event : history) {
      if (event.isTextual()
          && event.textValue().startsWith("rejected:" + VERIFIED_REFUTATION_PREFIX)) {
        return true;
      }
    }
    return false;
  }

  private static List<String> normalized(List<String> values) {
    if (values == null) {
      return List.of();
    }
    return values.stream()
        .map(value -> java.util.Objects.requireNonNull(value, "claim id").strip())
        .filter(value -> !value.isEmpty())
        .distinct()
        .sorted()
        .toList();
  }
}
