package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record NoveltySignature(
    @JsonProperty(value = "core_objects") @ContractNonNull List<String> coreObjects,
    @JsonProperty(value = "extension_tags") @ContractNonNull List<String> extensionTags,
    @JsonProperty(value = "key_transformations") @ContractNonNull List<String> keyTransformations,
    @JsonProperty(value = "mechanism_tags") @ContractNonNull List<String> mechanismTags,
    @JsonProperty(value = "normalization_confidence") Double normalizationConfidence,
    @JsonProperty(value = "normalized_hash") @ContractNonNull String normalizedHash,
    @JsonProperty(value = "normalizer_version") String normalizerVersion,
    @JsonProperty(value = "proof_principles") @ContractNonNull List<String> proofPrinciples,
    @JsonProperty(value = "raw_tags") @ContractNonNull Map<String, List<String>> rawTags,
    @JsonProperty(value = "representation_tags") @ContractNonNull List<String> representationTags,
    @JsonProperty(value = "targeted_obligation_ids") @ContractNonNull List<String> targetedObligationIds
) implements StrictContract {

  public NoveltySignature {
    if (coreObjects == null) {
      coreObjects = List.of();
    }
    coreObjects = ImmutableCollections.listOrEmpty(coreObjects);
    if (extensionTags == null) {
      extensionTags = List.of();
    }
    extensionTags = ImmutableCollections.listOrEmpty(extensionTags);
    if (keyTransformations == null) {
      keyTransformations = List.of();
    }
    keyTransformations = ImmutableCollections.listOrEmpty(keyTransformations);
    if (mechanismTags == null) {
      mechanismTags = List.of();
    }
    mechanismTags = ImmutableCollections.listOrEmpty(mechanismTags);
    ContractValues.minimum("normalization_confidence", normalizationConfidence, 0.0);
    ContractValues.maximum("normalization_confidence", normalizationConfidence, 1.0);
    if (normalizedHash == null) {
      normalizedHash = "";
    }
    normalizedHash = ContractStrings.trim(normalizedHash);
    normalizerVersion = ContractStrings.trim(normalizerVersion);
    if (proofPrinciples == null) {
      proofPrinciples = List.of();
    }
    proofPrinciples = ImmutableCollections.listOrEmpty(proofPrinciples);
    if (rawTags == null) {
      rawTags = Map.of();
    }
    rawTags = ImmutableCollections.stringListMapOrEmpty(rawTags);
    if (representationTags == null) {
      representationTags = List.of();
    }
    representationTags = ImmutableCollections.listOrEmpty(representationTags);
    if (targetedObligationIds == null) {
      targetedObligationIds = List.of();
    }
    targetedObligationIds = ImmutableCollections.listOrEmpty(targetedObligationIds);
    normalizedHash =
        ContractHashes.checked(
            "novelty signature hash",
            normalizedHash,
            CanonicalJson.stableHash(
                ContractHashes.noveltyPayload(
                    representationTags,
                    mechanismTags,
                    coreObjects,
                    keyTransformations,
                    proofPrinciples,
                    targetedObligationIds,
                    extensionTags)));
  }

  public NoveltySignature() {
    this(null, null, null, null, null, null, null, null, null, null, null);
  }

  @JsonIgnore
  public ObjectNode normalizedPayload() {
    return ContractHashes.noveltyPayload(
        representationTags,
        mechanismTags,
        coreObjects,
        keyTransformations,
        proofPrinciples,
        targetedObligationIds,
        extensionTags);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<String> coreObjects() {
    return coreObjects == null ? null : List.copyOf(coreObjects);
  }

  public List<String> extensionTags() {
    return extensionTags == null ? null : List.copyOf(extensionTags);
  }

  public List<String> keyTransformations() {
    return keyTransformations == null ? null : List.copyOf(keyTransformations);
  }

  public List<String> mechanismTags() {
    return mechanismTags == null ? null : List.copyOf(mechanismTags);
  }

  public List<String> proofPrinciples() {
    return proofPrinciples == null ? null : List.copyOf(proofPrinciples);
  }

  public Map<String, List<String>> rawTags() {
    return rawTags == null ? null : ImmutableCollections.copyStringListMap(rawTags);
  }

  public List<String> representationTags() {
    return representationTags == null ? null : List.copyOf(representationTags);
  }

  public List<String> targetedObligationIds() {
    return targetedObligationIds == null ? null : List.copyOf(targetedObligationIds);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
