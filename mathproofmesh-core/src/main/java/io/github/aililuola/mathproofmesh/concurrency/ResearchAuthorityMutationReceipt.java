package io.github.aililuola.mathproofmesh.concurrency;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Content-addressed receipt for one stable epoch authority mutation. */
@SuppressFBWarnings(
    value = "EI_EXPOSE_REP",
    justification = "The compact constructor creates immutable collection copies.")
public record ResearchAuthorityMutationReceipt(
    String epochId,
    String mergePlanHash,
    String authorityHashBefore,
    String authorityHashAfter,
    List<String> acceptedResultHashes,
    List<String> projectedClaimIds,
    List<String> factMessageIds,
    List<String> refutedObligationIds,
    String receiptHash) {
  public ResearchAuthorityMutationReceipt {
    epochId = required(epochId, "epochId");
    mergePlanHash = required(mergePlanHash, "mergePlanHash");
    authorityHashBefore = required(authorityHashBefore, "authorityHashBefore");
    authorityHashAfter = required(authorityHashAfter, "authorityHashAfter");
    acceptedResultHashes = orderedDistinct(acceptedResultHashes, "acceptedResultHashes");
    projectedClaimIds = sortedDistinct(projectedClaimIds, "projectedClaimIds");
    factMessageIds = sortedDistinct(factMessageIds, "factMessageIds");
    refutedObligationIds = sortedDistinct(refutedObligationIds, "refutedObligationIds");
    String expected =
        CanonicalJson.stableHash(
            new ReceiptPayload(
                epochId,
                mergePlanHash,
                authorityHashBefore,
                authorityHashAfter,
                acceptedResultHashes,
                projectedClaimIds,
                factMessageIds,
                refutedObligationIds));
    receiptHash = receiptHash == null || receiptHash.isBlank() ? expected : receiptHash.strip();
    if (!sameHash(expected, receiptHash)) {
      throw new IllegalArgumentException("research authority mutation receipt hash mismatch");
    }
  }

  public static ResearchAuthorityMutationReceipt create(
      String epochId,
      String mergePlanHash,
      String authorityHashBefore,
      String authorityHashAfter,
      List<String> acceptedResultHashes,
      List<String> projectedClaimIds,
      List<String> factMessageIds,
      List<String> refutedObligationIds) {
    return new ResearchAuthorityMutationReceipt(
        epochId,
        mergePlanHash,
        authorityHashBefore,
        authorityHashAfter,
        acceptedResultHashes,
        projectedClaimIds,
        factMessageIds,
        refutedObligationIds,
        null);
  }

  private static List<String> orderedDistinct(List<String> values, String label) {
    List<String> normalized = normalize(values, label);
    if (new LinkedHashSet<>(normalized).size() != normalized.size()) {
      throw new IllegalArgumentException(label + " must not contain duplicates");
    }
    return List.copyOf(normalized);
  }

  private static List<String> sortedDistinct(List<String> values, String label) {
    return normalize(values, label).stream().distinct().sorted().toList();
  }

  private static List<String> normalize(List<String> values, String label) {
    if (values == null) {
      return List.of();
    }
    return values.stream().map(value -> required(value, label + " entry")).toList();
  }

  private static String required(String value, String label) {
    String normalized = Objects.requireNonNull(value, label).strip();
    if (normalized.isEmpty()) {
      throw new IllegalArgumentException(label + " must not be blank");
    }
    return normalized;
  }

  private static boolean sameHash(String left, String right) {
    return MessageDigest.isEqual(
        left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
  }

  private record ReceiptPayload(
      String epochId,
      String mergePlanHash,
      String authorityHashBefore,
      String authorityHashAfter,
      List<String> acceptedResultHashes,
      List<String> projectedClaimIds,
      List<String> factMessageIds,
      List<String> refutedObligationIds) {}
}
