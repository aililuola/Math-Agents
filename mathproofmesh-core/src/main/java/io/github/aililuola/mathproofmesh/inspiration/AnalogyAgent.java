package io.github.aililuola.mathproofmesh.inspiration;

import io.github.aililuola.mathproofmesh.contract.AnalogyMapping;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.NoveltySignature;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Converts only verified local records into bounded analogy mappings. */
public final class AnalogyAgent {
  private final LocalAnalogyLibrary library;
  private final int topK;

  public AnalogyAgent(LocalAnalogyLibrary library, int topK) {
    this.library = java.util.Objects.requireNonNull(library, "library");
    if (topK <= 0) {
      throw new IllegalArgumentException("topK must be positive");
    }
    this.topK = topK;
  }

  public List<AnalogyMapping> search(
      String normalizedProblem,
      String problemHash,
      List<String> targetObligationIds,
      LocalAnalogyLibrary.Query query) {
    List<AnalogyMapping> result = new ArrayList<>();
    LocalAnalogyLibrary.Query effective =
        new LocalAnalogyLibrary.Query(
            normalizedProblem,
            query.objectTags(),
            query.operationTags(),
            query.mechanismTags(),
            query.graphTags(),
            query.obligationKinds(),
            query.mechanismChain(),
            query.graphMotifTags());
    for (LocalAnalogyLibrary.Record record : library.search(effective, problemHash, topK)) {
      mapping(record, problemHash, targetObligationIds).ifPresent(result::add);
    }
    return List.copyOf(result);
  }

  public java.util.Optional<AnalogyMapping> mapping(
      LocalAnalogyLibrary.Record record,
      String problemHash,
      List<String> targetObligationIds) {
    if (!record.verified()
        || record.objectCorrespondence().isEmpty()
        || record.operationCorrespondence().isEmpty()
        || record.transferableLemmas().isEmpty()
        || record.nonTransferableConditions().isEmpty()
        || record.transferRisks().isEmpty()) {
      return java.util.Optional.empty();
    }
    NoveltySignature signature =
        new NoveltySignature(
            new ArrayList<>(record.objectCorrespondence().values()),
            List.of(),
            new ArrayList<>(record.operationCorrespondence().values()),
            concat("structural_analogy", record.mechanismTags()),
            null,
            null,
            null,
            record.proofPrinciples(),
            Map.of(),
            record.representationTags(),
            targetObligationIds);
    String id =
        "analogy_"
            + CanonicalJson.stableHash(
                    List.of(record.recordId(), problemHash, signature.normalizedHash()))
                .substring(0, 12);
    return java.util.Optional.of(
        new AnalogyMapping(
            id,
            record.nonTransferableConditions(),
            signature,
            record.objectCorrespondence(),
            record.operationCorrespondence(),
            record.requiredBridgeLemmas(),
            record.problemSummary(),
            record.recordId(),
            problemHash,
            record.transferRisks(),
            record.transferableLemmas()));
  }

  private static List<String> concat(String first, List<String> rest) {
    List<String> result = new ArrayList<>();
    result.add(first);
    result.addAll(rest);
    return List.copyOf(result);
  }
}
