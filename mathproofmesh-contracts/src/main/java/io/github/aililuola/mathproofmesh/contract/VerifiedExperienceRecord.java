package io.github.aililuola.mathproofmesh.contract;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record VerifiedExperienceRecord(
    @JsonProperty(value = "cited_by_final_proof") @ContractNonNull Boolean citedByFinalProof,
    @JsonProperty(value = "graph_tags") @ContractNonNull List<String> graphTags,
    @JsonProperty(value = "key_construction") @ContractNonNull String keyConstruction,
    @JsonProperty(value = "mechanism_chain") @ContractNonNull List<String> mechanismChain,
    @JsonProperty(value = "mechanism_tags") @ContractNonNull List<String> mechanismTags,
    @JsonProperty(value = "negative_transfer_examples") @ContractNonNull List<String> negativeTransferExamples,
    @JsonProperty(value = "non_transferable_conditions") @ContractNonNull List<String> nonTransferableConditions,
    @JsonProperty(value = "object_correspondence") @ContractNonNull Map<String, String> objectCorrespondence,
    @JsonProperty(value = "object_tags") @ContractNonNull List<String> objectTags,
    @JsonProperty(value = "obligation_graph_motif") @ContractNonNull List<String> obligationGraphMotif,
    @JsonProperty(value = "obligation_kinds") @ContractNonNull List<String> obligationKinds,
    @JsonProperty(value = "operation_correspondence") @ContractNonNull Map<String, String> operationCorrespondence,
    @JsonProperty(value = "operation_tags") @ContractNonNull List<String> operationTags,
    @JsonProperty(value = "problem_hash", required = true) @ContractNonNull String problemHash,
    @JsonProperty(value = "problem_skeleton", required = true) @ContractNonNull String problemSkeleton,
    @JsonProperty(value = "problem_summary") @ContractNonNull String problemSummary,
    @JsonProperty(value = "proof_principles") @ContractNonNull List<String> proofPrinciples,
    @JsonProperty(value = "proof_summary") @ContractNonNull String proofSummary,
    @JsonProperty(value = "record_id", required = true) @ContractNonNull String recordId,
    @JsonProperty(value = "representation_tags") @ContractNonNull List<String> representationTags,
    @JsonProperty(value = "required_bridge_lemmas") @ContractNonNull List<String> requiredBridgeLemmas,
    @JsonProperty(value = "source_proposal_id", required = true) @ContractNonNull String sourceProposalId,
    @JsonProperty(value = "transfer_risks") @ContractNonNull List<String> transferRisks,
    @JsonProperty(value = "transferable_lemmas") @ContractNonNull List<String> transferableLemmas,
    @JsonProperty(value = "verified") @ContractNonNull Boolean verified
) implements StrictContract {

  public VerifiedExperienceRecord {
    if (citedByFinalProof == null) {
      citedByFinalProof = false;
    }
    if (graphTags == null) {
      graphTags = List.of();
    }
    graphTags = ImmutableCollections.listOrEmpty(graphTags);
    if (keyConstruction == null) {
      keyConstruction = "";
    }
    keyConstruction = ContractStrings.trim(keyConstruction);
    if (mechanismChain == null) {
      mechanismChain = List.of();
    }
    mechanismChain = ImmutableCollections.listOrEmpty(mechanismChain);
    if (mechanismTags == null) {
      mechanismTags = List.of();
    }
    mechanismTags = ImmutableCollections.listOrEmpty(mechanismTags);
    if (negativeTransferExamples == null) {
      negativeTransferExamples = List.of();
    }
    negativeTransferExamples = ImmutableCollections.listOrEmpty(negativeTransferExamples);
    if (nonTransferableConditions == null) {
      nonTransferableConditions = List.of();
    }
    nonTransferableConditions = ImmutableCollections.listOrEmpty(nonTransferableConditions);
    if (objectCorrespondence == null) {
      objectCorrespondence = Map.of();
    }
    objectCorrespondence = ImmutableCollections.mapOrEmpty(objectCorrespondence);
    if (objectTags == null) {
      objectTags = List.of();
    }
    objectTags = ImmutableCollections.listOrEmpty(objectTags);
    if (obligationGraphMotif == null) {
      obligationGraphMotif = List.of();
    }
    obligationGraphMotif = ImmutableCollections.listOrEmpty(obligationGraphMotif);
    if (obligationKinds == null) {
      obligationKinds = List.of();
    }
    obligationKinds = ImmutableCollections.listOrEmpty(obligationKinds);
    if (operationCorrespondence == null) {
      operationCorrespondence = Map.of();
    }
    operationCorrespondence = ImmutableCollections.mapOrEmpty(operationCorrespondence);
    if (operationTags == null) {
      operationTags = List.of();
    }
    operationTags = ImmutableCollections.listOrEmpty(operationTags);
    problemHash = ContractStrings.trim(problemHash);
    problemHash = ContractStrings.required("problem_hash", problemHash);
    problemSkeleton = ContractStrings.trim(problemSkeleton);
    problemSkeleton = ContractStrings.required("problem_skeleton", problemSkeleton);
    if (problemSummary == null) {
      problemSummary = "";
    }
    problemSummary = ContractStrings.trim(problemSummary);
    if (proofPrinciples == null) {
      proofPrinciples = List.of();
    }
    proofPrinciples = ImmutableCollections.listOrEmpty(proofPrinciples);
    if (proofSummary == null) {
      proofSummary = "";
    }
    proofSummary = ContractStrings.trim(proofSummary);
    recordId = ContractStrings.trim(recordId);
    recordId = ContractStrings.required("record_id", recordId);
    if (representationTags == null) {
      representationTags = List.of();
    }
    representationTags = ImmutableCollections.listOrEmpty(representationTags);
    if (requiredBridgeLemmas == null) {
      requiredBridgeLemmas = List.of();
    }
    requiredBridgeLemmas = ImmutableCollections.listOrEmpty(requiredBridgeLemmas);
    sourceProposalId = ContractStrings.trim(sourceProposalId);
    sourceProposalId = ContractStrings.required("source_proposal_id", sourceProposalId);
    if (transferRisks == null) {
      transferRisks = List.of();
    }
    transferRisks = ImmutableCollections.listOrEmpty(transferRisks);
    if (transferableLemmas == null) {
      transferableLemmas = List.of();
    }
    transferableLemmas = ImmutableCollections.listOrEmpty(transferableLemmas);
    if (verified == null) {
      verified = true;
    }
    ContractValues.constant("verified", verified, true);
  }

  // BEGIN GENERATED DEFENSIVE ACCESSORS
  public List<String> graphTags() {
    return graphTags == null ? null : List.copyOf(graphTags);
  }

  public List<String> mechanismChain() {
    return mechanismChain == null ? null : List.copyOf(mechanismChain);
  }

  public List<String> mechanismTags() {
    return mechanismTags == null ? null : List.copyOf(mechanismTags);
  }

  public List<String> negativeTransferExamples() {
    return negativeTransferExamples == null ? null : List.copyOf(negativeTransferExamples);
  }

  public List<String> nonTransferableConditions() {
    return nonTransferableConditions == null ? null : List.copyOf(nonTransferableConditions);
  }

  public Map<String, String> objectCorrespondence() {
    return objectCorrespondence == null ? null : Map.copyOf(objectCorrespondence);
  }

  public List<String> objectTags() {
    return objectTags == null ? null : List.copyOf(objectTags);
  }

  public List<String> obligationGraphMotif() {
    return obligationGraphMotif == null ? null : List.copyOf(obligationGraphMotif);
  }

  public List<String> obligationKinds() {
    return obligationKinds == null ? null : List.copyOf(obligationKinds);
  }

  public Map<String, String> operationCorrespondence() {
    return operationCorrespondence == null ? null : Map.copyOf(operationCorrespondence);
  }

  public List<String> operationTags() {
    return operationTags == null ? null : List.copyOf(operationTags);
  }

  public List<String> proofPrinciples() {
    return proofPrinciples == null ? null : List.copyOf(proofPrinciples);
  }

  public List<String> representationTags() {
    return representationTags == null ? null : List.copyOf(representationTags);
  }

  public List<String> requiredBridgeLemmas() {
    return requiredBridgeLemmas == null ? null : List.copyOf(requiredBridgeLemmas);
  }

  public List<String> transferRisks() {
    return transferRisks == null ? null : List.copyOf(transferRisks);
  }

  public List<String> transferableLemmas() {
    return transferableLemmas == null ? null : List.copyOf(transferableLemmas);
  }
  // END GENERATED DEFENSIVE ACCESSORS
}
