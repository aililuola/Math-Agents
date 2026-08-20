package io.github.aililuola.mathproofmesh.concurrency;

import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

public record ResearchAuthorityMutationSnapshot(
    List<ResearchAuthorityMutationReceipt> authorityMutations,
    List<ResearchMergeReceipt> mergeReceipts,
    long version,
    String stableHash) {
  public ResearchAuthorityMutationSnapshot {
    authorityMutations =
        authorityMutations == null
            ? List.of()
            : authorityMutations.stream()
                .sorted(Comparator.comparing(ResearchAuthorityMutationReceipt::epochId))
                .toList();
    mergeReceipts =
        mergeReceipts == null
            ? List.of()
            : mergeReceipts.stream()
                .sorted(Comparator.comparing(ResearchMergeReceipt::epochId))
                .toList();
    requireUniqueEpochs(
        authorityMutations.stream().map(ResearchAuthorityMutationReceipt::epochId).toList(),
        "authority mutation");
    requireUniqueEpochs(
        mergeReceipts.stream().map(ResearchMergeReceipt::epochId).toList(), "merge receipt");
    if (version < 0L) {
      throw new IllegalArgumentException("version must be nonnegative");
    }
    String expected =
        CanonicalJson.stableHash(
            Map.of(
                "authority_mutations", authorityMutations,
                "merge_receipts", mergeReceipts,
                "version", version));
    stableHash = stableHash == null || stableHash.isBlank() ? expected : stableHash.strip();
    if (!MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8),
        stableHash.getBytes(StandardCharsets.UTF_8))) {
      throw new IllegalArgumentException("research authority mutation snapshot hash mismatch");
    }
  }

  public static ResearchAuthorityMutationSnapshot empty() {
    return new ResearchAuthorityMutationSnapshot(List.of(), List.of(), 0L, null);
  }

  @Override
  public List<ResearchAuthorityMutationReceipt> authorityMutations() {
    return List.copyOf(authorityMutations);
  }

  @Override
  public List<ResearchMergeReceipt> mergeReceipts() {
    return List.copyOf(mergeReceipts);
  }

  private static void requireUniqueEpochs(List<String> epochIds, String label) {
    if (new HashSet<>(epochIds).size() != epochIds.size()) {
      throw new IllegalArgumentException(label + " epoch IDs must be unique");
    }
  }
}
