package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record MechanismChainSignature(
    @JsonProperty(value = "bridge_pattern") @ContractNonNull List<String> bridgePattern,
    @JsonProperty(value = "chain_hash") @ContractNonNull String chainHash,
    @JsonProperty(value = "representation") @ContractNonNull List<String> representation,
    @JsonProperty(value = "terminal_argument") @ContractNonNull List<String> terminalArgument,
    @JsonProperty(value = "transformations") @ContractNonNull List<String> transformations
) implements StrictContract {

  public MechanismChainSignature {
    if (bridgePattern == null) {
      bridgePattern = List.of();
    }
    bridgePattern = ImmutableCollections.listOrEmpty(bridgePattern);
    if (chainHash == null) {
      chainHash = "";
    }
    chainHash = ContractStrings.trim(chainHash);
    if (representation == null) {
      representation = List.of();
    }
    representation = ImmutableCollections.listOrEmpty(representation);
    if (terminalArgument == null) {
      terminalArgument = List.of();
    }
    terminalArgument = ImmutableCollections.listOrEmpty(terminalArgument);
    if (transformations == null) {
      transformations = List.of();
    }
    transformations = ImmutableCollections.listOrEmpty(transformations);
    chainHash =
        ContractHashes.checked(
            "mechanism chain hash",
            chainHash,
            CanonicalJson.stableHash(
                ContractHashes.mechanismChainPayload(
                    representation, transformations, bridgePattern, terminalArgument)));
  }

  public MechanismChainSignature() {
    this(null, null, null, null, null);
  }

  @JsonIgnore
  public ObjectNode normalizedPayload() {
    return ContractHashes.mechanismChainPayload(
        representation, transformations, bridgePattern, terminalArgument);
  }

  @JsonIgnore
  public boolean complete() {
    ObjectNode payload = normalizedPayload();
    int populatedStages = 0;
    int componentCount = 0;
    for (JsonNode values : payload) {
      if (!values.isEmpty()) {
        populatedStages++;
      }
      componentCount += values.size();
    }
    return populatedStages >= 3 && componentCount >= 4;
  }

  public static MechanismChainSignature fromNoveltySignature(
      NoveltySignature signature) {
    ContractValues.required("signature", signature);
    return new MechanismChainSignature(
        signature.mechanismTags(),
        null,
        signature.representationTags(),
        signature.proofPrinciples(),
        signature.keyTransformations());
  }

  public NoveltySignature toNoveltySignature(List<String> targetedObligationIds) {
    return new NoveltySignature(
        null,
        null,
        transformations,
        bridgePattern,
        null,
        null,
        null,
        terminalArgument,
        null,
        representation,
        targetedObligationIds == null ? List.of() : targetedObligationIds);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<String> bridgePattern() {
    return bridgePattern == null ? null : List.copyOf(bridgePattern);
  }

  public List<String> representation() {
    return representation == null ? null : List.copyOf(representation);
  }

  public List<String> terminalArgument() {
    return terminalArgument == null ? null : List.copyOf(terminalArgument);
  }

  public List<String> transformations() {
    return transformations == null ? null : List.copyOf(transformations);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
